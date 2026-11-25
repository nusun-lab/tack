package us.nusun.tack.framework.inference;

import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.apache.camel.Exchange;
import org.apache.camel.component.langchain4j.agent.api.AiAgentBody;
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
            ResponseFormat format = requestFormat(exchange(context, job("low-pass", SCHEMA), true));

            assertNotNull(format);
            assertEquals(ResponseFormatType.JSON, format.type());
            assertNotNull(format.jsonSchema());
            assertEquals("camel_schema", format.jsonSchema().name());
        }
    }

    @Test
    void nonSchemaJobDoesNotSetProviderResponseFormat() throws Exception {
        try (DefaultCamelContext context = new DefaultCamelContext()) {
            ResponseFormat format = requestFormat(exchange(context, job("low-pass", null), true));

            assertNull(format);
        }
    }

    @Test
    void disabledProviderSchemaDoesNotSetResponseFormatEvenWhenResponseSchemaExists() throws Exception {
        try (DefaultCamelContext context = new DefaultCamelContext()) {
            ResponseFormat format = requestFormat(exchange(context, job("low-pass", SCHEMA), false));

            assertNull(format);
        }
    }

    @Test
    void forceProviderSchemaMetadataSetsResponseFormatWhenDefaultIsDisabled() throws Exception {
        try (DefaultCamelContext context = new DefaultCamelContext()) {
            ResponseFormat format = requestFormat(exchange(context, job(
                    "low-pass",
                    SCHEMA,
                    Map.of("llmForceProviderSchema", true)), false));

            assertNotNull(format);
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
            assertNotNull(requestFormat(worker));
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

    private static ResponseFormat requestFormat(Exchange exchange) {
        var model = new ChatModel() {
            ResponseFormat responseFormat;

            @Override
            public ChatResponse doChat(ChatRequest request) {
                responseFormat = request.responseFormat();
                return ChatResponse.builder().aiMessage(AiMessage.from("{\"result\":\"ok\"}")).build();
            }
        };
        var agent = FrameworkFixtures.agentFactory("low-pass", model,
                new us.nusun.tack.framework.tool.mcp.McpClientFactory(), new TestTokenCountEstimator(), 12000, 4, null)
                .createAgent(exchange);
        agent.chat(new AiAgentBody<>("Return the result.", "Return JSON.", null), null);
        return model.responseFormat;
    }
}
