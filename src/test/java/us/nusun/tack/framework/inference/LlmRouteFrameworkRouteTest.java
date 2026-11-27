package us.nusun.tack.framework.inference;

import dev.langchain4j.service.Result;
import org.apache.camel.CamelContext;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.langchain4j.agent.api.Agent;
import org.apache.camel.component.langchain4j.agent.api.AgentFactory;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.component.kafka.consumer.KafkaManualCommit;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.model.ToDynamicDefinition;
import org.apache.camel.model.TryDefinition;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmRouteFrameworkRouteTest {
    @Test
    void nestedLlmRouteReturnsResultWithoutDeadLetterForCallerFailure() throws Exception {
        InFlightReplyManager replies = new InFlightReplyManager();
        StubKafkaComponent kafka = new StubKafkaComponent((topic, exchange) -> {
            LlmJobEnvelope queued = exchange.getProperty("llmJob", LlmJobEnvelope.class);
            if (queued != null && "high-pass".equals(queued.laneId()) && queued.passType() == LlmPassType.ACTION) {
                replies.complete(LlmJobResult.succeeded(queued, "test-model", "{\"result\":\"ok\"}", "{\"result\":\"ok\"}", Instant.now()));
            }
        });

        try (DefaultCamelContext context = context(replies, kafka)) {
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:caller")
                            .to("direct:llm")
                            .throwException(new IllegalStateException("Caller failed after inference"));
                }
            });
            context.start();

            var request = new LlmJobEnvelope(null, "high-pass", LlmPassType.ACTION, null, null, 0, "Return a JSON result.",
                    null, "classpath:schemas/framework/text-result-schema.json",
                    Map.of("personaId", FrameworkFixtures.PERSONA_A, "agentRunId", "run:/high?partition=stable",
                            "toolProfile", FrameworkFixtures.PROFILE_GAMMA), null);
            Exchange result = context.createProducerTemplate().request("direct:caller",
                    exchange -> exchange.getMessage().setBody(request));
            assertEquals("Caller failed after inference", result.getException().getMessage());
            LlmJobResult body = result.getMessage().getBody(LlmJobResult.class);
            assertEquals(LlmJobStatus.SUCCEEDED, body.status());
            assertEquals("high-pass", body.laneId());
            assertEquals(LlmPassType.ACTION, body.passType());
            assertEquals("{\"result\":\"ok\"}", body.content());
            context.stop();
            assertEquals(List.of("test.llm.requests.high"), kafka.sentTopics());
            assertEquals("run:/high?partition=stable", kafka.sentTo("test.llm.requests.high").getFirst().getIn().getHeader(KafkaConstants.KEY));
            assertEquals(0, replies.inFlightCount());
        }
    }

    @Test
    void kafkaPublishEndpointsDoNotInterpolateDynamicKeysIntoUris() throws Exception {
        try (DefaultCamelContext context = context(new InFlightReplyManager(), new StubKafkaComponent())) {
            String requestUri = context.getRouteDefinition("llm").getOutputs().stream()
                    .filter(TryDefinition.class::isInstance)
                    .flatMap(step -> step.getOutputs().stream())
                    .filter(ToDynamicDefinition.class::isInstance)
                    .map(ToDynamicDefinition.class::cast)
                    .map(ToDynamicDefinition::getUri)
                    .findFirst()
                    .orElseThrow();
            String replyInput = context.getRouteDefinition("publish-llm-result-low-pass").getOutputs().toString();
            String deadLetterOutput = context.getRouteDefinition("publish-failed-llm-job-dlq").getOutputs().toString();

            assertEquals("kafka:${exchangeProperty.laneRequestTopic}?synchronous=true", requestUri);
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
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:caller").to("direct:llm");
                }
            });
            context.start();

            CamelExecutionException failure = assertThrows(
                    CamelExecutionException.class,
                    () -> context.createProducerTemplate().requestBody("direct:caller", structuredRequest("Return invalid JSON for the schema.")));

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
    void unownedRequestIsCommittedWithoutExecuting() throws Exception {
        InFlightReplyManager replies = new InFlightReplyManager();
        StubKafkaComponent kafka = new StubKafkaComponent();

        try (DefaultCamelContext context = context(replies, kafka)) {
            context.start();
            LlmJobEnvelope queued = FrameworkFixtures.queuedJob(new us.nusun.tack.framework.inference.LlmJobEnvelope(
                    null, "low-pass", LlmPassType.ACTION, "test", "Persona A", 0, "prompt", "system", null, Map.of(), null));
            AtomicInteger committed = new AtomicInteger();
            emitWorkerRequest(kafka, TestJson.write(queued), committed::incrementAndGet);
            assertEquals(1, committed.get());
            assertTrue(kafka.sentTopics().isEmpty());
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
            replies.register(queued.jobId());

            AtomicInteger committed = new AtomicInteger();
            emitWorkerRequest(kafka, json, committed::incrementAndGet);
            emitWorkerRequest(kafka, json, committed::incrementAndGet);

            await(() -> kafka.sentTopics().contains("test.llm.replies.low"));
            assertEquals(2, committed.get());
            assertEquals(1, kafka.sentTo("test.llm.replies.low").size());
            assertEquals(LlmJobStatus.SUCCEEDED, replies.await(queued.jobId(), 100).status());
            String replyJson = kafka.sentTo("test.llm.replies.low").getFirst().getIn().getBody(String.class);
            assertTrue(replyJson.contains("\"jobId\":\"" + queued.jobId() + "\""));
            assertTrue(replyJson.contains("\"status\":\"SUCCEEDED\""));
            assertTrue(replyJson.contains("\"content\":\"{\\\"result\\\":\\\"worker-ok\\\"}\""));
        }
    }

    @Test
    void failedOffsetCommitCannotClaimOrExecuteTheJob() throws Exception {
        InFlightReplyManager replies = new InFlightReplyManager();
        StubKafkaComponent kafka = new StubKafkaComponent();

        try (DefaultCamelContext context = context(replies, kafka)) {
            context.start();
            LlmJobEnvelope queued = FrameworkFixtures.queuedJob(structuredRequest("Return JSON."));
            replies.register(queued.jobId());
            String json = TestJson.write(queued);
            try {
                emitWorkerRequest(kafka, json, () -> { throw new IllegalStateException("Partition was revoked"); });
            } catch (Exception failure) {
                assertTrue(failure.getMessage().contains("Partition was revoked"));
            }
            assertTrue(kafka.sentTopics().isEmpty());

            emitWorkerRequest(kafka, json, () -> {});
            assertEquals(LlmJobStatus.SUCCEEDED, replies.await(queued.jobId(), 100).status());
        }
    }

    @Test
    void replyTopicFailureDoesNotLoseTheTerminalResultOrReplayTheWorker() throws Exception {
        InFlightReplyManager replies = new InFlightReplyManager();
        StubKafkaComponent kafka = new StubKafkaComponent((topic, exchange) -> {
            if ("test.llm.replies.low".equals(topic)) {
                throw new IllegalStateException("Reply topic unavailable");
            }
        });

        try (DefaultCamelContext context = context(replies, kafka)) {
            context.start();
            LlmJobEnvelope queued = FrameworkFixtures.queuedJob(structuredRequest("Return JSON."));
            replies.register(queued.jobId());
            String json = TestJson.write(queued);
            try {
                emitWorkerRequest(kafka, json, () -> {});
            } catch (Exception failure) {
                assertTrue(failure.getMessage().contains("Reply topic unavailable"));
            }
            assertEquals(LlmJobStatus.SUCCEEDED, replies.await(queued.jobId(), 100).status());

            emitWorkerRequest(kafka, json, () -> {});
            assertEquals(1, kafka.sentTo("test.llm.replies.low").size());
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

            replies.register(queued.jobId());
            emitWorkerRequest(kafka, TestJson.write(queued), () -> {});

            await(() -> kafka.sentTopics().contains("test.llm.replies.low") && kafka.sentTopics().contains("llm.dead-letter"));
            assertEquals(LlmJobStatus.FAILED, replies.await(queued.jobId(), 100).status());
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

    private static void emitWorkerRequest(StubKafkaComponent kafka, String json, KafkaManualCommit commit) throws Exception {
        kafka.emit("test.llm.requests.low", json, exchange -> exchange.getMessage().setHeader(KafkaConstants.MANUAL_COMMIT, commit));
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
        properties.setProperty("llm.model-default.openai.model", "test-model");
        properties.setProperty("llm.reply-timeout-ms", "100");
        properties.setProperty("llm.topic.dead-letter", "llm.dead-letter");
        context.getPropertiesComponent().setOverrideProperties(properties);
        context.addComponent("kafka", kafka);
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
