package us.nusun.tack.framework.inference;

import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.apache.camel.Exchange;
import org.apache.camel.component.langchain4j.agent.api.AbstractAgent;
import org.apache.camel.component.langchain4j.agent.api.Agent;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import us.nusun.tack.framework.tests.support.TestTokenCountEstimator;
import us.nusun.tack.framework.tests.support.FrameworkFixtures;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProviderSchemaConfigurationTest {
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
    void schemaJobSetsProviderResponseFormatOnAgentFactoryAgent() throws Exception {
        try (DefaultCamelContext context = new DefaultCamelContext()) {
            Agent agent = factory(context).createAgent(exchange(context, job("low-pass", SCHEMA), true));

            ResponseFormat responseFormat = ((AbstractAgent<?>) agent).getResponseFormat();
            assertNotNull(responseFormat);
            assertEquals(ResponseFormatType.JSON, responseFormat.type());
            assertNotNull(responseFormat.jsonSchema());
            assertEquals("camel_schema", responseFormat.jsonSchema().name());
        }
    }

    @Test
    void nonSchemaJobDoesNotSetProviderResponseFormat() throws Exception {
        try (DefaultCamelContext context = new DefaultCamelContext()) {
            Agent agent = factory(context).createAgent(exchange(context, job("low-pass", null), true));

            assertNull(((AbstractAgent<?>) agent).getResponseFormat());
        }
    }

    @Test
    void disabledProviderSchemaDoesNotSetResponseFormatEvenWhenResponseSchemaExists() throws Exception {
        try (DefaultCamelContext context = new DefaultCamelContext()) {
            Agent agent = factory(context).createAgent(exchange(context, job("low-pass", SCHEMA), false));

            assertNull(((AbstractAgent<?>) agent).getResponseFormat());
        }
    }

    @Test
    void forceProviderSchemaMetadataSetsResponseFormatWhenDefaultIsDisabled() throws Exception {
        try (DefaultCamelContext context = new DefaultCamelContext()) {
            Agent agent = factory(context).createAgent(exchange(context, job(
                    "low-pass",
                    SCHEMA,
                    Map.of("llmForceProviderSchema", true)), false));

            assertNotNull(((AbstractAgent<?>) agent).getResponseFormat());
        }
    }

    @Test
    void workerDefaultStillEnablesProviderSchemaWhenWireForceFlagIsFalse() throws Exception {
        try (var context = new DefaultCamelContext()) {
            Exchange producer = exchange(context, job("low-pass", SCHEMA, Map.of("llmForceProviderSchema", false)), false);
            var queued = producer.getMessage().getBody(LlmJobEnvelope.class);
            assertEquals(false, queued.metadata().get("llmForceProviderSchema"));

            Exchange worker = new DefaultExchange(context);
            worker.setProperty("llmJob", queued);
            FrameworkFixtures.jobFactory(FrameworkFixtures.inferenceLaneRegistry(), "test-model", true).restore(worker);
            assertNotNull(((AbstractAgent<?>) factory(context).createAgent(worker)).getResponseFormat());
        }
    }

    private static Exchange exchange(DefaultCamelContext context, LlmJobEnvelope job, boolean enabled) {
        Exchange exchange = new DefaultExchange(context);
        exchange.getMessage().setBody(job);
        FrameworkFixtures.jobFactory(FrameworkFixtures.inferenceLaneRegistry(), "test-model", enabled).prepare(exchange);
        return exchange;
    }

    private static LlmJobEnvelope job(String laneId, String schema) {
        return job(laneId, schema, Map.of());
    }

    private static LlmJobEnvelope job(String laneId, String schema, Map<String, Object> metadata) {
        return new LlmJobEnvelope(
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
                null);
    }

    private static LlmAgentFactory.DynamicAgentFactory factory(DefaultCamelContext context) {
        LlmAgentFactory.DynamicAgentFactory factory = FrameworkFixtures.agentFactory(
                "low-pass", chatModel(), new us.nusun.tack.framework.tool.mcp.McpClientFactory(), new TestTokenCountEstimator(), 12000, 4, null);
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
