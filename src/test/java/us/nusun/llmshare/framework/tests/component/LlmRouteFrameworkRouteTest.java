package us.nusun.llmshare.framework.tests.component;

import org.apache.camel.CamelExecutionException;
import org.apache.camel.Consumer;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultComponent;
import org.apache.camel.support.DefaultConsumer;
import org.apache.camel.support.DefaultEndpoint;
import org.apache.camel.support.DefaultProducer;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.llm.InFlightReplyManager;
import us.nusun.llmshare.framework.model.LlmJobEnvelope;
import us.nusun.llmshare.framework.model.LlmJobResult;
import us.nusun.llmshare.framework.model.LlmJobStatus;
import us.nusun.llmshare.framework.route.LlmRoute;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;
import us.nusun.llmshare.framework.tests.support.TestCamelContexts;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmRouteFrameworkRouteTest {
    @Test
    void directLlmRouteQueuesKafkaRequestAwaitsReplyAndReturnsSuccessfulResult() throws Exception {
        InFlightReplyManager replies = new InFlightReplyManager();
        StubKafkaComponent kafka = new StubKafkaComponent((topic, exchange) -> {
            if (topic.startsWith("llm.requests.high")) {
                LlmJobEnvelope queued = exchange.getProperty("llmJob", LlmJobEnvelope.class);
                replies.complete(LlmJobResult.succeeded(queued, "test-model", "{\"result\":\"ok\"}", "{\"result\":\"ok\"}", Instant.now()));
            }
        });

        try (DefaultCamelContext context = context(replies, kafka)) {
            context.start();

            Exchange result = context.createProducerTemplate().request("direct:llm", exchange -> {
                exchange.setProperty("personaId", FrameworkFixtures.PERSONA_A);
                exchange.setProperty("toolProfile", "source-control");
                exchange.getIn().setHeader("executionRuntime", "tool-high");
                exchange.getIn().setHeader("responseSchema", "classpath:schemas/framework/text-result-schema.json");
                exchange.getIn().setBody("Return a JSON result.");
            });

            LlmJobResult body = result.getMessage().getBody(LlmJobResult.class);
            assertEquals(LlmJobStatus.SUCCEEDED, body.status());
            assertEquals("tool-high", body.requester());
            assertEquals("{\"result\":\"ok\"}", body.content());
            assertEquals(List.of("llm.requests.high"), kafka.sentTopics());
            assertEquals(0, replies.inFlightCount());
        }
    }

    @Test
    void directLlmRoutePublishesDeadLetterAndClearsInFlightReplyWhenSchemaValidationFails() throws Exception {
        InFlightReplyManager replies = new InFlightReplyManager();
        StubKafkaComponent kafka = new StubKafkaComponent((topic, exchange) -> {
            if (topic.startsWith("llm.requests.low")) {
                LlmJobEnvelope queued = exchange.getProperty("llmJob", LlmJobEnvelope.class);
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
                                    "executionRuntime", "synthesis-low",
                                    "responseSchema", "classpath:schemas/framework/text-result-schema.json")));

            assertTrue(failure.getMessage().contains("Exception occurred during execution"));
            assertEquals(0, replies.inFlightCount());
            await(() -> kafka.sentTopics().contains("llm.dead-letter"));
            Exchange deadLetterExchange = kafka.sentTo("llm.dead-letter").getFirst();
            String deadLetterJson = deadLetterExchange.getIn().getBody(String.class);
            assertTrue(deadLetterJson.contains("\"runtimeId\":\"synthesis-low\""));
            assertTrue(deadLetterJson.contains("\"failureMessage\""));
        }
    }

    @Test
    void handleResponseRouteCompletesRegisteredInFlightReply() throws Exception {
        InFlightReplyManager replies = new InFlightReplyManager();
        StubKafkaComponent kafka = new StubKafkaComponent();

        try (DefaultCamelContext context = context(replies, kafka)) {
            context.start();
            LlmJobEnvelope queued = LlmJobEnvelope.queued(new us.nusun.llmshare.framework.model.LlmJobRequest(
                    null, "tool-low", "Persona A", 0, "prompt", "system", null, Map.of(), null).normalized());
            replies.register(queued);
            LlmJobResult result = LlmJobResult.succeeded(queued, "test-model", "ok", "ok", Instant.now());

            context.createProducerTemplate().sendBody("direct:handle-llm-response", result);

            LlmJobResult completed = replies.await(queued.jobId(), 10);
            assertEquals(result.jobId(), completed.jobId());
            assertEquals("ok", completed.content());
        }
    }

    private static DefaultCamelContext context(InFlightReplyManager replies, StubKafkaComponent kafka) throws Exception {
        DefaultCamelContext context = TestCamelContexts.quiet();
        context.setAutowiredEnabled(false);
        context.getShutdownStrategy().setTimeout(1);
        Properties properties = new Properties();
        properties.setProperty("llm.consumer.group", "framework-test");
        properties.setProperty("llm.response.consumer.group", "framework-test-response");
        properties.setProperty("llm.openai.model", "test-model");
        properties.setProperty("llm.reply-timeout-ms", "100");
        properties.setProperty("llm.topic.dead-letter", "llm.dead-letter");
        context.getPropertiesComponent().setOverrideProperties(properties);
        context.addComponent("kafka", kafka);
        context.getRegistry().bind("inFlightReplyManager", replies);
        context.getRegistry().bind("executionRuntimeAgentFactory", new Object());
        FrameworkFixtures.bindCatalogBeans(context);
        LlmRoute route = new LlmRoute();
        FrameworkFixtures.set(route, "camelContext", context);
        FrameworkFixtures.set(route, "runtimes", FrameworkFixtures.executionRuntimeRegistry());
        FrameworkFixtures.set(route, "personas", FrameworkFixtures.personaCatalog());
        FrameworkFixtures.set(route, "inFlightReplyManager", replies);
        context.addRoutes(route);
        return context;
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

    private interface SendObserver {
        void sent(String topic, Exchange exchange) throws Exception;
    }

    private static final class StubKafkaComponent extends DefaultComponent {
        private final SendObserver observer;
        private final List<SentExchange> sent = new ArrayList<>();

        private StubKafkaComponent() {
            this((topic, exchange) -> { });
        }

        private StubKafkaComponent(SendObserver observer) {
            this.observer = observer;
        }

        @Override
        protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) {
            return new StubKafkaEndpoint(uri, this, remaining);
        }

        private List<String> sentTopics() {
            return sent.stream().map(SentExchange::topic).toList();
        }

        private List<Exchange> sentTo(String topic) {
            return sent.stream()
                    .filter(item -> item.topic().equals(topic))
                    .map(SentExchange::exchange)
                    .toList();
        }
    }

    private static final class StubKafkaEndpoint extends DefaultEndpoint {
        private final StubKafkaComponent component;
        private final String topic;

        private StubKafkaEndpoint(String endpointUri, StubKafkaComponent component, String topic) {
            super(endpointUri, component);
            this.component = component;
            this.topic = topic;
        }

        @Override
        public Producer createProducer() {
            return new DefaultProducer(this) {
                @Override
                public void process(Exchange exchange) throws Exception {
                    Exchange copy = exchange.copy();                    component.sent.add(new SentExchange(topic, copy));
                    component.observer.sent(topic, exchange);
                }
            };
        }

        @Override
        public Consumer createConsumer(Processor processor) {
            return new DefaultConsumer(this, processor);
        }

        @Override
        public boolean isSingleton() {
            return true;
        }

        @Override
        public boolean isLenientProperties() {
            return true;
        }
    }

    private record SentExchange(String topic, Exchange exchange) {
    }
}
