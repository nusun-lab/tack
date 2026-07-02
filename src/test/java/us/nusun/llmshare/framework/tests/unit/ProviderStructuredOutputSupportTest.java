package us.nusun.llmshare.framework.tests.unit;

import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.apache.camel.Exchange;
import org.apache.camel.component.langchain4j.agent.api.AbstractAgent;
import org.apache.camel.component.langchain4j.agent.api.Agent;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.llm.InFlightReplyManager;
import us.nusun.llmshare.framework.llm.LlmAgentFactory;
import us.nusun.llmshare.framework.llm.LlmDeadLetterFactory;
import us.nusun.llmshare.framework.llm.LlmRouteSupport;
import us.nusun.llmshare.framework.llm.ProviderStructuredOutputSupport;
import us.nusun.llmshare.framework.memory.ApproximateTokenCountEstimator;
import us.nusun.llmshare.framework.model.LlmJobEnvelope;
import us.nusun.llmshare.framework.model.LlmJobRequest;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;

import java.util.Map;
import java.util.Properties;
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
    void agentFactoryRuntimeUriDoesNotAppendJsonSchemaWhenProviderSchemaIsEnabled() throws Exception {
        try (DefaultCamelContext context = context(true)) {
            Exchange exchange = new DefaultExchange(context);
            LlmJobEnvelope job = job("synthesis-low", SCHEMA);
            LlmRouteSupport support = new LlmRouteSupport(
                    context,
                    FrameworkFixtures.executionRuntimeRegistry(),
                    new InFlightReplyManager(),
                    new LlmDeadLetterFactory());

            support.prepareAgentCall(job, exchange);

            String uri = exchange.getProperty("agentInvocationUri", String.class);
            assertEquals("langchain4j-agent:synthesis-low?agentFactory=#executionRuntimeAgentFactory", uri);
            assertTrue(!uri.contains("jsonSchema"));
        }
    }

    @Test
    void schemaJobSetsProviderResponseFormatOnAgentFactoryAgent() throws Exception {
        try (DefaultCamelContext context = context(true)) {
            Agent agent = factory(context).createAgent(exchange(context, job("synthesis-low", SCHEMA)));

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
            Agent agent = factory(context).createAgent(exchange(context, job("synthesis-low", null)));

            assertNull(((AbstractAgent<?>) agent).getResponseFormat());
        }
    }

    @Test
    void disabledProviderSchemaDoesNotSetResponseFormatEvenWhenResponseSchemaExists() throws Exception {
        try (DefaultCamelContext context = context(false)) {
            Agent agent = factory(context).createAgent(exchange(context, job("synthesis-low", SCHEMA)));

            assertNull(((AbstractAgent<?>) agent).getResponseFormat());
        }
    }

    @Test
    void forceProviderSchemaMetadataSetsResponseFormatWhenDefaultIsDisabled() throws Exception {
        try (DefaultCamelContext context = context(false)) {
            Agent agent = factory(context).createAgent(exchange(context, job(
                    "synthesis-low",
                    SCHEMA,
                    Map.of(ProviderStructuredOutputSupport.FORCE_PROVIDER_SCHEMA_PROPERTY, true))));

            assertNotNull(((AbstractAgent<?>) agent).getResponseFormat());
        }
    }

    private static DefaultCamelContext context(boolean providerSchemaEnabled) {
        DefaultCamelContext context = new DefaultCamelContext();
        Properties properties = new Properties();
        properties.setProperty("llm.runtime-default.structured-output.provider-schema-enabled", String.valueOf(providerSchemaEnabled));
        context.getPropertiesComponent().setOverrideProperties(properties);
        return context;
    }

    private static Exchange exchange(DefaultCamelContext context, LlmJobEnvelope job) {
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("executionRuntimeId", job.requester());
        exchange.setProperty("llmJob", job);
        return exchange;
    }

    private static LlmJobEnvelope job(String runtimeId, String schema) {
        return job(runtimeId, schema, Map.of());
    }

    private static LlmJobEnvelope job(String runtimeId, String schema, Map<String, Object> metadata) {
        return LlmJobEnvelope.queued(new LlmJobRequest(
                UUID.randomUUID(),
                runtimeId,
                "test",
                0,
                "Return the result.",
                "Return only JSON.",
                schema,
                metadata,
                null).normalized());
    }

    private static LlmAgentFactory.ExecutionRuntimeAgentFactory factory(DefaultCamelContext context) {
        LlmAgentFactory.ExecutionRuntimeAgentFactory factory = new LlmAgentFactory.ExecutionRuntimeAgentFactory(
                FrameworkFixtures.executionRuntimeRegistry(),
                Map.of("synthesis-low", chatModel()),
                null,
                null,
                null,
                new ApproximateTokenCountEstimator(),
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
