package us.nusun.tack.framework.tests.unit;

import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.apache.camel.Exchange;
import org.apache.camel.component.langchain4j.agent.api.AbstractAgent;
import org.apache.camel.component.langchain4j.agent.api.Agent;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import us.nusun.tack.framework.llm.InFlightReplyManager;
import us.nusun.tack.framework.llm.LlmAgentFactory;
import us.nusun.tack.framework.llm.LlmDeadLetterFactory;
import us.nusun.tack.framework.llm.LlmRouteSupport;
import us.nusun.tack.framework.llm.ProviderStructuredOutputSupport;
import us.nusun.tack.framework.tests.support.TestTokenCountEstimator;
import us.nusun.tack.framework.model.LlmJobEnvelope;
import us.nusun.tack.framework.model.LlmJobRequest;
import us.nusun.tack.framework.model.LlmPassType;
import us.nusun.tack.framework.tests.support.FrameworkFixtures;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderStructuredOutputSupportTest {
    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "result": { "type": "string" }
              },
              "required": ["result"],
              "additionalProperties": false
            }
            """;

    @Test
    void dynamicAgentUriDoesNotAppendJsonSchemaWhenProviderSchemaIsEnabled() throws Exception {
        try (DefaultCamelContext context = context(true)) {
            Exchange exchange = new DefaultExchange(context);
            LlmJobEnvelope job = job("low-pass", SCHEMA);
            LlmRouteSupport support = new LlmRouteSupport(
                    context,
                    FrameworkFixtures.inferenceLaneRegistry(),
                    new InFlightReplyManager(),
                    new LlmDeadLetterFactory());

            support.prepareAgentCall(job, exchange);

            String uri = exchange.getProperty("agentInvocationUri", String.class);
            assertEquals("langchain4j-agent:dynamic?agentFactory=#dynamicAgentFactory", uri);
            assertTrue(!uri.contains("jsonSchema"));
        }
    }


    @Test
    void laneModelConstructionCreatesOneChatModelPerInferenceLane() throws Exception {
        Method laneModels = LlmAgentFactory.class.getDeclaredMethod(
                "laneModels",
                us.nusun.tack.framework.inventory.InferenceLaneRegistry.class,
                String.class,
                String.class,
                String.class,
                double.class,
                double.class,
                double.class,
                int.class,
                double.class,
                double.class,
                int.class,
                long.class,
                boolean.class,
                String.class,
                boolean.class);
        laneModels.setAccessible(true);

        Map<?, ?> models = (Map<?, ?>) laneModels.invoke(
                null,
                FrameworkFixtures.inferenceLaneRegistry(),
                "http://localhost:9999/v1",
                "test-key",
                "global-model",
                0.0,
                1.0,
                0.0,
                -1,
                0.0,
                1.0,
                128,
                1000L,
                false,
                null,
                true);

        assertEquals(Set.of("low-pass", "high-pass"), models.keySet());
        assertEquals(2, models.values().size());
        assertEquals(2, Set.copyOf(models.values()).size());
    }

    @Test
    void schemaJobSetsProviderResponseFormatOnAgentFactoryAgent() throws Exception {
        try (DefaultCamelContext context = context(true)) {
            Agent agent = factory(context).createAgent(exchange(context, job("low-pass", SCHEMA)));

            ResponseFormat responseFormat = ((AbstractAgent<?>) agent).getResponseFormat();
            assertNotNull(responseFormat);
            assertEquals(ResponseFormatType.JSON, responseFormat.type());
            assertNotNull(responseFormat.jsonSchema());
            assertEquals("camel_schema", responseFormat.jsonSchema().name());
        }
    }

    @Test
    void nonSchemaJobDoesNotSetProviderResponseFormat() throws Exception {
        try (DefaultCamelContext context = context(true)) {
            Agent agent = factory(context).createAgent(exchange(context, job("low-pass", null)));

            assertNull(((AbstractAgent<?>) agent).getResponseFormat());
        }
    }

    @Test
    void disabledProviderSchemaDoesNotSetResponseFormatEvenWhenResponseSchemaExists() throws Exception {
        try (DefaultCamelContext context = context(false)) {
            Agent agent = factory(context).createAgent(exchange(context, job("low-pass", SCHEMA)));

            assertNull(((AbstractAgent<?>) agent).getResponseFormat());
        }
    }

    @Test
    void forceProviderSchemaMetadataSetsResponseFormatWhenDefaultIsDisabled() throws Exception {
        try (DefaultCamelContext context = context(false)) {
            Agent agent = factory(context).createAgent(exchange(context, job(
                    "low-pass",
                    SCHEMA,
                    Map.of(ProviderStructuredOutputSupport.FORCE_PROVIDER_SCHEMA_PROPERTY, true))));

            assertNotNull(((AbstractAgent<?>) agent).getResponseFormat());
        }
    }

    private static DefaultCamelContext context(boolean providerSchemaEnabled) {
        DefaultCamelContext context = new DefaultCamelContext();
        Properties properties = new Properties();
        properties.setProperty("llm.model-default.structured-output.provider-schema-enabled", String.valueOf(providerSchemaEnabled));
        context.getPropertiesComponent().setOverrideProperties(properties);
        return context;
    }

    private static Exchange exchange(DefaultCamelContext context, LlmJobEnvelope job) {
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("laneId", job.laneId());
        exchange.setProperty("passType", job.passType());
        exchange.setProperty("llmJob", job);
        return exchange;
    }

    private static LlmJobEnvelope job(String laneId, String schema) {
        return job(laneId, schema, Map.of());
    }

    private static LlmJobEnvelope job(String laneId, String schema, Map<String, Object> metadata) {
        return LlmJobEnvelope.queued(new LlmJobRequest(
                UUID.randomUUID(),
                laneId,
                LlmPassType.SYNTHESIS,
                "test",
                "test",
                0,
                "Return the result.",
                "Return only JSON.",
                schema,
                metadata,
                null).normalized());
    }

    private static LlmAgentFactory.DynamicAgentFactory factory(DefaultCamelContext context) {
        LlmAgentFactory.DynamicAgentFactory factory = new LlmAgentFactory.DynamicAgentFactory(
                FrameworkFixtures.inferenceLaneRegistry(),
                Map.of("low-pass", chatModel()),
                null,
                null,
                null,
                new TestTokenCountEstimator(),
                12000);
        factory.setCamelContext(context);
        return factory;
    }

    private static OpenAiChatModel chatModel() {
        return OpenAiChatModel.builder()
                .baseUrl("http://localhost:9999/v1")
                .apiKey("test-key")
                .modelName("test-model")
                .build();
    }
}
