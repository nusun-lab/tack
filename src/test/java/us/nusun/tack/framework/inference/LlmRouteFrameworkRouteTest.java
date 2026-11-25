package us.nusun.tack.framework.inference;

import dev.langchain4j.service.Result;
import org.apache.camel.CamelContext;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.Exchange;
import org.apache.camel.component.langchain4j.agent.api.Agent;
import org.apache.camel.component.langchain4j.agent.api.AgentFactory;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.impl.DefaultCamelContext;
import org.jboss.logmanager.LogContext;
import org.jboss.logmanager.Logger;
import org.junit.jupiter.api.Test;
import us.nusun.tack.framework.tests.support.FrameworkFixtures;
import us.nusun.tack.framework.logging.LogTokens;
import us.nusun.tack.framework.tests.support.StubKafkaComponent;
import us.nusun.tack.framework.tests.support.TestCamelContexts;
import us.nusun.tack.framework.tests.support.TestJson;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmRouteFrameworkRouteTest {
    @Test
    void directLlmRouteQueuesKafkaRequestAwaitsReplyAndReturnsSuccessfulResult() throws Exception {
        InFlightReplyManager replies = new InFlightReplyManager();
        StubKafkaComponent kafka = new StubKafkaComponent((topic, exchange) -> {
            LlmJobEnvelope queued = exchange.getProperty("llmJob", LlmJobEnvelope.class);
            if (queued != null && "high-pass".equals(queued.laneId()) && queued.passType() == LlmPassType.ACTION) {
                replies.complete(LlmJobResult.succeeded(queued, "test-model", "{\"result\":\"ok\"}", "{\"result\":\"ok\"}", Instant.now()));
            }
        });

        try (DefaultCamelContext context = context(replies, kafka)) {
            context.start();

            LlmJobResult body = context.createProducerTemplate().requestBody("direct:llm",
                    new LlmJobEnvelope(null, "high-pass", LlmPassType.ACTION, null, null, 0, "Return a JSON result.",
                            null, "classpath:schemas/framework/text-result-schema.json",
                            Map.of("personaId", FrameworkFixtures.PERSONA_A, "agentRunId", "run:/high?partition=stable",
                                    "toolProfile", FrameworkFixtures.PROFILE_GAMMA), null), LlmJobResult.class);
            assertEquals(LlmJobStatus.SUCCEEDED, body.status());
            assertEquals("high-pass", body.laneId());
            assertEquals(LlmPassType.ACTION, body.passType());
            assertEquals("{\"result\":\"ok\"}", body.content());
            assertEquals(List.of("test.llm.requests.high"), kafka.sentTopics());
            assertEquals("run:/high?partition=stable", kafka.sentTo("test.llm.requests.high").getFirst().getIn().getHeader(KafkaConstants.KEY));
            assertEquals(0, replies.inFlightCount());
        }
    }

    @Test
    void kafkaPublishEndpointsDoNotInterpolateDynamicKeysIntoUris() throws Exception {
        try (DefaultCamelContext context = context(new InFlightReplyManager(), new StubKafkaComponent())) {
            String requestUri = context.getRouteDefinition("llm").getOutputs().stream()
                    .map(Object::toString)
                    .filter(value -> value.contains("kafka:${exchangeProperty.laneRequestTopic}"))
                    .findFirst()
                    .orElse("");
            String replyInput = context.getRouteDefinition("publish-llm-result-low-pass").getOutputs().toString();
            String deadLetterOutput = context.getRouteDefinition("publish-failed-llm-job-dlq").getOutputs().toString();

            assertTrue(requestUri.contains("RecipientList"));
            assertTrue(requestUri.contains("kafka:${exchangeProperty.laneRequestTopic}"));
            assertTrue(!requestUri.contains("language:simple"));
            assertTrue(replyInput.contains("kafka:test.llm.replies.low"));
            assertTrue(deadLetterOutput.contains("kafka:{{llm.topic.dead-letter:llm.dead-letter}}"));
            assertTrue(!requestUri.contains("?key="));
            assertTrue(!replyInput.contains("?key="));
            assertTrue(!deadLetterOutput.contains("?key="));
        }
    }

    @Test
    void directLlmRoutePublishesDeadLetterAndClearsInFlightReplyWhenSchemaValidationFails() throws Exception {
        InFlightReplyManager replies = new InFlightReplyManager();
        StubKafkaComponent kafka = new StubKafkaComponent((topic, exchange) -> {
            LlmJobEnvelope queued = exchange.getProperty("llmJob", LlmJobEnvelope.class);
            if (queued != null && "low-pass".equals(queued.laneId()) && queued.passType() == LlmPassType.SYNTHESIS) {
                replies.complete(LlmJobResult.succeeded(queued, "test-model", "{\"wrong\":true}", "{\"wrong\":true}", Instant.now()));
            }
        });

        Logger camelErrorHandler = LogContext.getLogContext()
                .getLogger("org.apache.camel.processor.errorhandler.DefaultErrorHandler");
        java.util.logging.Level previousLevel = camelErrorHandler.getLevel();
        camelErrorHandler.setLevel(java.util.logging.Level.OFF);
        try (DefaultCamelContext context = context(replies, kafka)) {
            context.start();

            CamelExecutionException failure = assertThrows(
                    CamelExecutionException.class,
                    () -> context.createProducerTemplate().requestBody("direct:llm", structuredRequest("Return invalid JSON for the schema.")));

            assertTrue(failure.getMessage().contains("Exception occurred during execution"));
            assertEquals(0, replies.inFlightCount());
            await(() -> kafka.sentTopics().contains("llm.dead-letter"));
            Exchange deadLetterExchange = kafka.sentTo("llm.dead-letter").getFirst();
            String deadLetterJson = deadLetterExchange.getIn().getBody(String.class);
            assertTrue(deadLetterJson.contains("\"laneId\":\"low-pass\""));
            assertTrue(deadLetterJson.contains("\"passType\":\"SYNTHESIS\""));
            assertTrue(!deadLetterJson.contains("runtime" + "Id"));
            assertTrue(deadLetterJson.contains("\"failureMessage\""));
            assertTrue(deadLetterExchange.getIn().getHeader(KafkaConstants.KEY) instanceof UUID);
        } finally {
            camelErrorHandler.setLevel(previousLevel);
        }
    }

    @Test
    void directLlmRouteReportsMalformedStructuredOutputWithBoundedResponseContext() throws Exception {
        InFlightReplyManager replies = new InFlightReplyManager();
        StubKafkaComponent kafka = new StubKafkaComponent((topic, exchange) -> {
            LlmJobEnvelope queued = exchange.getProperty("llmJob", LlmJobEnvelope.class);
            if (queued != null && "low-pass".equals(queued.laneId()) && queued.passType() == LlmPassType.SYNTHESIS) {
                String truncated = "{\"result\":\"cut off";
                replies.complete(LlmJobResult.succeeded(queued, "test-model", truncated, truncated, Instant.now()));
            }
        });

        Logger camelErrorHandler = LogContext.getLogContext()
                .getLogger("org.apache.camel.processor.errorhandler.DefaultErrorHandler");
        java.util.logging.Level previousLevel = camelErrorHandler.getLevel();
        camelErrorHandler.setLevel(java.util.logging.Level.OFF);
        try (DefaultCamelContext context = context(replies, kafka)) {
            context.start();

            assertThrows(
                    CamelExecutionException.class,
                    () -> context.createProducerTemplate().requestBody("direct:llm", structuredRequest("Return truncated JSON.")));

            await(() -> kafka.sentTopics().contains("llm.dead-letter"));
            String deadLetterJson = kafka.sentTo("llm.dead-letter").getFirst().getIn().getBody(String.class);
            assertTrue(deadLetterJson.contains("reason=malformed-structured-output"));
            assertTrue(deadLetterJson.contains("lane=low-pass"));
            assertTrue(deadLetterJson.contains("passType=SYNTHESIS"));
            assertTrue(deadLetterJson.contains("responseChars=18"));
            assertTrue(deadLetterJson.contains("responseTail"));
        } finally {
            camelErrorHandler.setLevel(previousLevel);
        }
    }

    @Test
    void handleResponseRouteCompletesRegisteredInFlightReply() throws Exception {
        InFlightReplyManager replies = new InFlightReplyManager();
        StubKafkaComponent kafka = new StubKafkaComponent();

        try (DefaultCamelContext context = context(replies, kafka)) {
            context.start();
            LlmJobEnvelope queued = FrameworkFixtures.queuedJob(new us.nusun.tack.framework.inference.LlmJobEnvelope(
                    null, "low-pass", LlmPassType.ACTION, "test", "Persona A", 0, "prompt", "system", null, Map.of(), null));
            replies.register(queued.jobId());
            LlmJobResult result = LlmJobResult.succeeded(queued, "test-model", "ok", "ok", Instant.now());

            context.createProducerTemplate().sendBody("direct:handle-llm-response", result);

            LlmJobResult completed = replies.await(queued.jobId(), 10);
            assertEquals(result.jobId(), completed.jobId());
            assertEquals("ok", completed.content());
        }
    }

    @Test
    void llmWorkerPublishesSuccessfulAgentResultToReplyTopic() throws Exception {
        InFlightReplyManager replies = new InFlightReplyManager();
        StubKafkaComponent kafka = new StubKafkaComponent();

        try (DefaultCamelContext context = context(replies, kafka)) {
            context.start();
            LlmJobEnvelope queued = FrameworkFixtures.queuedJob(new LlmJobEnvelope(
                    UUID.randomUUID(),
                    "low-pass",
                    LlmPassType.SYNTHESIS,
                    "test",
                    "test",
                    0,
                    "Return JSON.",
                    "Return only JSON.",
                    null,
                    Map.of(),
                    null));
            String json = TestJson.write(queued);

            kafka.emit("test.llm.requests.low", json);

            await(() -> kafka.sentTopics().contains("test.llm.replies.low"));
            String replyJson = kafka.sentTo("test.llm.replies.low").getFirst().getIn().getBody(String.class);
            assertTrue(replyJson.contains("\"jobId\":\"" + queued.jobId() + "\""));
            assertTrue(replyJson.contains("\"status\":\"SUCCEEDED\""));
            assertTrue(replyJson.contains("\"content\":\"{\\\"result\\\":\\\"worker-ok\\\"}\""));
        }
    }

    @Test
    void llmWorkerConvertsAgentValidationFailureToFailedReplyAndDeadLetter() throws Exception {
        InFlightReplyManager replies = new InFlightReplyManager();
        StubKafkaComponent kafka = new StubKafkaComponent();

        try (DefaultCamelContext context = context(replies, kafka, nullBodyAgentFactory())) {
            context.start();
            LlmJobEnvelope queued = FrameworkFixtures.queuedJob(new LlmJobEnvelope(
                    UUID.randomUUID(),
                    "low-pass",
                    LlmPassType.SYNTHESIS,
                    "test",
                    "test",
                    0,
                    "Return JSON.",
                    "Return only JSON.",
                    null,
                    Map.of("primitive", "test-synthesis"),
                    null));

            kafka.emit("test.llm.requests.low", TestJson.write(queued));

            await(() -> kafka.sentTopics().contains("test.llm.replies.low") && kafka.sentTopics().contains("llm.dead-letter"));
            String replyJson = kafka.sentTo("test.llm.replies.low").getFirst().getIn().getBody(String.class);
            assertTrue(replyJson.contains("\"jobId\":\"" + queued.jobId() + "\""));
            assertTrue(replyJson.contains("\"status\":\"FAILED\""));
            assertTrue(replyJson.contains("LLM response failure: reason=empty-final-content"));

            Exchange deadLetterExchange = kafka.sentTo("llm.dead-letter").getFirst();
            String deadLetterJson = deadLetterExchange.getIn().getBody(String.class);
            assertTrue(deadLetterJson.contains("\"jobId\":\"" + queued.jobId() + "\""));
            assertTrue(deadLetterJson.contains("\"laneId\":\"low-pass\""));
            assertTrue(deadLetterJson.contains("\"passType\":\"SYNTHESIS\""));
            assertTrue(deadLetterJson.contains("LLM response failure: reason=empty-final-content"));
            assertTrue(deadLetterExchange.getIn().getHeader(KafkaConstants.KEY) instanceof UUID);
        }
    }

    private static LlmJobEnvelope structuredRequest(String prompt) {
        return new LlmJobEnvelope(null, "low-pass", LlmPassType.SYNTHESIS, null, null, 0, prompt,
                null, "classpath:schemas/framework/text-result-schema.json", Map.of(), null);
    }

    private static DefaultCamelContext context(InFlightReplyManager replies, StubKafkaComponent kafka) throws Exception {
        return context(replies, kafka, fakeAgentFactory());
    }

    private static DefaultCamelContext context(InFlightReplyManager replies, StubKafkaComponent kafka, AgentFactory agentFactory) throws Exception {
        DefaultCamelContext context = TestCamelContexts.quiet();
        context.setAutowiredEnabled(false);
        context.getShutdownStrategy().setTimeout(1);
        Properties properties = new Properties();
        properties.setProperty("llm.consumer.group", "framework-test");
        properties.setProperty("llm.response.consumer.group", "framework-test-response");
        properties.setProperty("llm.model-default.openai.model", "test-model");
        properties.setProperty("llm.reply-timeout-ms", "100");
        properties.setProperty("llm.topic.dead-letter", "llm.dead-letter");
        context.getPropertiesComponent().setOverrideProperties(properties);
        context.addComponent("kafka", kafka);
        context.getRegistry().bind("inFlightReplyManager", replies);
        context.getRegistry().bind("dynamicAgentFactory", agentFactory);
        FrameworkFixtures.bindCatalogBeans(context);
        LlmRoute route = new LlmRoute();
        FrameworkFixtures.set(route, "lanes", FrameworkFixtures.inferenceLaneRegistry());
        FrameworkFixtures.set(route, "jobFactory", FrameworkFixtures.jobFactory());
        LlmDeadLetterFactory deadLetters = new LlmDeadLetterFactory();
        FrameworkFixtures.set(route, "deadLetterFactory", deadLetters);
        FrameworkFixtures.set(route, "routeSupport", FrameworkFixtures.llmRouteSupport(replies, deadLetters, new LogTokens(false)));
        context.addRoutes(route);
        return context;
    }

    private static AgentFactory fakeAgentFactory() {
        return new AgentFactory() {
            private CamelContext camelContext;

            @Override
            public Agent createAgent(Exchange exchange) {
                return (aiAgentBody, toolProvider) -> Result.<String>builder()
                        .content("{\"result\":\"worker-ok\"}")
                        .build();
            }

            @Override
            public CamelContext getCamelContext() {
                return camelContext;
            }

            @Override
            public void setCamelContext(CamelContext camelContext) {
                this.camelContext = camelContext;
            }
        };
    }

    private static AgentFactory nullBodyAgentFactory() {
        return new AgentFactory() {
            private CamelContext camelContext;

            @Override
            public Agent createAgent(Exchange exchange) {
                return (aiAgentBody, toolProvider) -> Result.<String>builder()
                        .content(null)
                        .build();
            }

            @Override
            public CamelContext getCamelContext() {
                return camelContext;
            }

            @Override
            public void setCamelContext(CamelContext camelContext) {
                this.camelContext = camelContext;
            }
        };
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 1000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean());
    }
}
