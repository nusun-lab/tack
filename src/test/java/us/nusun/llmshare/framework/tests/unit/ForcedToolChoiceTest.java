package us.nusun.llmshare.framework.tests.unit;

import us.nusun.llmshare.framework.llm.LlmAgentFactory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.apache.camel.Exchange;
import org.apache.camel.component.langchain4j.agent.api.Agent;
import org.apache.camel.component.langchain4j.agent.api.AiAgentBody;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.model.LlmJobEnvelope;
import us.nusun.llmshare.framework.model.LlmJobRequest;
import us.nusun.llmshare.framework.model.LlmPassType;
import us.nusun.llmshare.framework.mcp.McpClientFactory;
import us.nusun.llmshare.framework.tests.support.TestTokenCountEstimator;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForcedToolChoiceTest {
    private static final String SCHEMA = """
            {
              \"type\": \"object\",
              \"properties\": {
                \"result\": { \"type\": \"string\" }
              },
              \"required\": [\"result\"],
              \"additionalProperties\": false
            }
            """;

    @Test
    void toolEnabledAgentRequiresToolChoiceUntilFirstToolResultThenReturnsAuto() {
        RecordingChatModel model = new RecordingChatModel(true);
        Agent agent = factory("low-pass", model).createAgent(exchange("low-pass", LlmPassType.ACTION));

        agent.chat(new AiAgentBody<>("Inspect current external state.", "You are a tester.", "memory-1"), probeToolProvider());

        assertEquals(2, model.requests.size());
        assertEquals(ToolChoice.REQUIRED, model.requests.get(0).toolChoice());
        assertEquals(ToolChoice.AUTO, model.requests.get(1).toolChoice());
        assertTrue(model.requests.get(1).messages().stream().anyMatch(ToolExecutionResultMessage.class::isInstance));
    }

    @Test
    void toolEnabledAgentPreservesProviderResponseFormat() {
        RecordingChatModel model = new RecordingChatModel(false);
        Exchange exchange = exchange("low-pass", LlmPassType.ACTION, context(true));
        exchange.setProperty("llmJob", job("low-pass", LlmPassType.ACTION, SCHEMA));
        Agent agent = factory("low-pass", model).createAgent(exchange);

        agent.chat(new AiAgentBody<>("Inspect current external state.", "You are a tester.", "memory-2"), probeToolProvider());

        assertEquals(1, model.requests.size());
        assertEquals(ToolChoice.REQUIRED, model.requests.get(0).toolChoice());
        assertNotNull(model.requests.get(0).responseFormat());
        assertEquals(ResponseFormatType.JSON, model.requests.get(0).responseFormat().type());
        assertNotNull(model.requests.get(0).responseFormat().jsonSchema());
    }

    @Test
    void synthesisOnlyAgentDoesNotSetToolChoice() {
        RecordingChatModel model = new RecordingChatModel(false);
        Agent agent = factory("low-pass", model).createAgent(exchange("low-pass", LlmPassType.SYNTHESIS));

        agent.chat(new AiAgentBody<>("Synthesize.", "Return JSON.", null), null);

        assertEquals(1, model.requests.size());
        assertNull(model.requests.get(0).toolChoice());
    }

    private static LlmAgentFactory.DynamicAgentFactory factory(String laneId, ChatModel model) {
        return new LlmAgentFactory.DynamicAgentFactory(
                FrameworkFixtures.inferenceLaneRegistry(),
                Map.of(laneId, model),
                new McpClientFactory(),
                FrameworkFixtures.toolProfileRegistry(),
                FrameworkFixtures.personaCatalog(),
                new TestTokenCountEstimator(),
                12000);
    }

    private static Exchange exchange(String laneId, LlmPassType passType) {
        return exchange(laneId, passType, new DefaultCamelContext());
    }

    private static Exchange exchange(String laneId, LlmPassType passType, DefaultCamelContext context) {
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("laneId", laneId);
        exchange.setProperty("passType", passType);
        exchange.setProperty("personaId", FrameworkFixtures.PERSONA_A);
        exchange.setProperty("toolProfile", "none");
        return exchange;
    }

    private static DefaultCamelContext context(boolean providerSchemaEnabled) {
        DefaultCamelContext context = new DefaultCamelContext();
        Properties properties = new Properties();
        properties.setProperty("llm.model-default.structured-output.provider-schema-enabled", String.valueOf(providerSchemaEnabled));
        context.getPropertiesComponent().setOverrideProperties(properties);
        return context;
    }

    private static LlmJobEnvelope job(String laneId, LlmPassType passType, String schema) {
        return LlmJobEnvelope.queued(new LlmJobRequest(
                UUID.randomUUID(),
                laneId,
                passType,
                "test",
                "test",
                0,
                "Return the result.",
                "Return only JSON.",
                schema,
                Map.of(),
                null).normalized());
    }

    private static ToolProvider probeToolProvider() {
        ToolSpecification tool = ToolSpecification.builder()
                .name("probe_tool")
                .description("Probe external state")
                .build();
        return request -> ToolProviderResult.builder()
                .add(tool, (toolRequest, memoryId) -> "tool result")
                .build();
    }

    private static final class RecordingChatModel implements ChatModel {
        private final boolean requestToolFirst;
        private final List<ChatRequest> requests = new ArrayList<>();

        private RecordingChatModel(boolean requestToolFirst) {
            this.requestToolFirst = requestToolFirst;
        }

        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            requests.add(chatRequest);
            if (requestToolFirst && requests.size() == 1) {
                return ChatResponse.builder()
                        .aiMessage(new AiMessage(List.of(ToolExecutionRequest.builder()
                                .id("call-1")
                                .name("probe_tool")
                                .arguments("{}")
                                .build())))
                        .build();
            }
            return ChatResponse.builder()
                    .aiMessage(new AiMessage("final response"))
                    .build();
        }
    }
}
