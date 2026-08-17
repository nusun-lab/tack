package us.nusun.llmshare.framework.tests.component;

import dev.langchain4j.service.Result;
import org.apache.camel.CamelContext;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.Exchange;
import org.apache.camel.component.langchain4j.agent.api.Agent;
import org.apache.camel.component.langchain4j.agent.api.AgentFactory;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.llm.InFlightReplyManager;
import us.nusun.llmshare.framework.llm.LlmDeadLetterFactory;
import us.nusun.llmshare.framework.llm.LlmJobFactory;
import us.nusun.llmshare.framework.llm.LlmRouteSupport;
import us.nusun.llmshare.framework.model.LlmJobEnvelope;
import us.nusun.llmshare.framework.model.LlmJobRequest;
import us.nusun.llmshare.framework.model.LlmJobResult;
import us.nusun.llmshare.framework.model.LlmJobStatus;
import us.nusun.llmshare.framework.model.LlmPassType;
import us.nusun.llmshare.framework.route.LlmRoute;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;
import us.nusun.llmshare.framework.tests.support.StubKafkaComponent;
import us.nusun.llmshare.framework.tests.support.TestCamelContexts;
import us.nusun.llmshare.framework.tests.support.TestJson;

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
            if (queued != null && "high-pass".equals(queued.laneId()) && queued.passType() == LlmPassType.TOOL) {
                replies.complete(LlmJobResult.succeeded(queued, "test-model", "{\"result\":\"ok\"}", "{\"result\":\"ok\"}", Instant.now()));
            }
        });

        try (DefaultCamelContext context = context(replies, kafka)) {
            context.start();

            Exchange result = context.createProducerTemplate().request("direct:llm", exchange -> {
                exchange.setProperty("personaId", FrameworkFixtures.PERSONA_A);
                exchange.setProperty("agentRunId", "run:/high?partition=stable");
                exchange.setProperty("toolProfile", FrameworkFixtures.PROFILE_GAMMA);
                exchange.getIn().setHeader("lane", "high-pass");
                exchange.getIn().setHeader("passType", LlmPassType.TOOL);
                exchange.getIn().setHeader("responseSchema", "classpath:schemas/framework/text-result-schema.json");
                exchange.getIn().setBody("Return a JSON result.");
            });

            LlmJobResult body = result.getMessage().getBody(LlmJobResult.class);
            assertEquals(LlmJobStatus.SUCCEEDED, body.status());
            assertEquals("high-pass", body.laneId());
            assertEquals(LlmPassType.TOOL, body.passType());
            assertEquals("{\"result\":\"ok\"}", body.content());
            assertEquals(List.of("llm.requests.high"), kafka.sentTopics());
            assertEquals("run:/high?partition=stable", kafka.sentTo("llm.requests.high").getFirst().getIn().getHeader(KafkaConstants.KEY));
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
            assertTrue(replyInput.contains("kafka:llm.replies.low"));
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

        try (DefaultCamelContext context = context(replies, kafka)) {
            context.start();

            CamelExecutionException failure = assertThrows(
                    CamelExecutionException.class,
                    () -> context.createProducerTemplate().requestBodyAndHeaders(
                            "direct:llm",
                            "Return invalid JSON for the schema.",
                            Map.of(
                                    "lane", "low-pass",
                                    "passType", LlmPassType.SYNTHESIS,
                                    "responseSchema", "classpath:schemas/framework/text-result-schema.json")));

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
        }
    }

    @Test
    void handleResponseRouteCompletesRegisteredInFlightReply() throws Exception {
        InFlightReplyManager replies = new InFlightReplyManager();
        StubKafkaComponent kafka = new StubKafkaComponent();

        try (DefaultCamelContext context = context(replies, kafka)) {
            context.start();
            LlmJobEnvelope queued = LlmJobEnvelope.queued(new us.nusun.llmshare.framework.model.LlmJobRequest(
                    null, "low-pass", LlmPassType.TOOL, "test", "Persona A", 0, "prompt", "system", null, Map.of(), null).normalized());
            replies.register(queued);
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
            LlmJobEnvelope queued = LlmJobEnvelope.queued(new LlmJobRequest(
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
                    null).normalized());
            String json = TestJson.write(queued);

            kafka.emit("llm.requests.low", json);

            await(() -> kafka.sentTopics().contains("llm.replies.low"));
            String replyJson = kafka.sentTo("llm.replies.low").getFirst().getIn().getBody(String.class);
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
            LlmJobEnvelope queued = LlmJobEnvelope.queued(new LlmJobRequest(
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
                    null).normalized());

            kafka.emit("llm.requests.low", TestJson.write(queued));

            await(() -> kafka.sentTopics().contains("llm.replies.low") && kafka.sentTopics().contains("llm.dead-letter"));
            String replyJson = kafka.sentTo("llm.replies.low").getFirst().getIn().getBody(String.class);
            assertTrue(replyJson.contains("\"jobId\":\"" + queued.jobId() + "\""));
            assertTrue(replyJson.contains("\"status\":\"FAILED\""));
            assertTrue(replyJson.contains("Validation failed for Predicate[body is not null]"));

            Exchange deadLetterExchange = kafka.sentTo("llm.dead-letter").getFirst();
            String deadLetterJson = deadLetterExchange.getIn().getBody(String.class);
            assertTrue(deadLetterJson.contains("\"jobId\":\"" + queued.jobId() + "\""));
            assertTrue(deadLetterJson.contains("\"laneId\":\"low-pass\""));
            assertTrue(deadLetterJson.contains("\"passType\":\"SYNTHESIS\""));
            assertTrue(deadLetterJson.contains("Validation failed for Predicate[body is not null]"));
            assertTrue(deadLetterExchange.getIn().getHeader(KafkaConstants.KEY) instanceof UUID);
        }
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
        FrameworkFixtures.set(route, "jobFactory", new LlmJobFactory(FrameworkFixtures.inferenceLaneRegistry(), FrameworkFixtures.personaCatalog()));
        LlmDeadLetterFactory deadLetters = new LlmDeadLetterFactory();
        FrameworkFixtures.set(route, "deadLetterFactory", deadLetters);
        FrameworkFixtures.set(route, "routeSupport", new LlmRouteSupport(
                context,
                FrameworkFixtures.inferenceLaneRegistry(),
                replies,
                deadLetters));
        context.addRoutes(route);
        return context;
    }

    private static AgentFactory fakeAgentFactory() {
        return new AgentFactory() {
            private CamelContext camelContext;

            @Override
            public Agent createAgent(Exchange exchange) {
                return (aiAgentBody, toolProvider) -> "{\"result\":\"worker-ok\"}";
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
                return (aiAgentBody, toolProvider) -> null;
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
