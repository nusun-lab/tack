package us.nusun.tack.framework.inference;

import us.nusun.tack.framework.logging.LogTokens;

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
import org.junit.jupiter.api.Test;
import us.nusun.tack.framework.tool.mcp.McpClientFactory;
import us.nusun.tack.framework.tests.support.TestTokenCountEstimator;
import us.nusun.tack.framework.tests.support.FrameworkFixtures;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        Exchange exchange = exchange("low-pass", LlmPassType.ACTION);
        FrameworkFixtures.restoreJob(exchange, job("low-pass", LlmPassType.ACTION, SCHEMA));
        Agent agent = factory("low-pass", model).createAgent(exchange);

        agent.chat(new AiAgentBody<>("Inspect current external state.", "You are a tester.", "memory-2"), probeToolProvider());

        assertEquals(1, model.requests.size());
        assertEquals(ToolChoice.REQUIRED, model.requests.get(0).toolChoice());
        assertNotNull(model.requests.get(0).responseFormat());
        assertEquals(ResponseFormatType.JSON, model.requests.get(0).responseFormat().type());
        assertNotNull(model.requests.get(0).responseFormat().jsonSchema());
    }

    @Test
    void promptLoggingWaitsUntilFirstToolBackedModelDispatchAndLogsOnlyOnceAcrossToolRounds() {
        RecordingChatModel model = new RecordingChatModel(true);
        CapturingLogTokens logs = new CapturingLogTokens();
        LlmRouteSupport routeSupport = routeSupport(logs);
        Exchange exchange = exchange("low-pass", LlmPassType.ACTION);
        FrameworkFixtures.restoreJob(exchange, job("low-pass", LlmPassType.ACTION, null));

        Agent agent = factory("low-pass", model, routeSupport).createAgent(exchange);

        assertEquals(0, logs.messages.size());

        agent.chat(new AiAgentBody<>("Inspect current external state.", "You are a tester.", "memory-log"), probeToolProvider());

        assertEquals(2, model.requests.size());
        assertEquals(1, logs.messages.size());
        assertTrue(logs.messages.getFirst().contains("PROMPT Return the result."));
    }

    @Test
    void promptLoggingWaitsUntilStatelessModelDispatch() {
        RecordingChatModel model = new RecordingChatModel(false);
        CapturingLogTokens logs = new CapturingLogTokens();
        LlmRouteSupport routeSupport = routeSupport(logs);
        Exchange exchange = exchange("low-pass", LlmPassType.SYNTHESIS);
        FrameworkFixtures.restoreJob(exchange, job("low-pass", LlmPassType.SYNTHESIS, null));

        Agent agent = factory("low-pass", model, routeSupport).createAgent(exchange);

        assertEquals(0, logs.messages.size());

        agent.chat(new AiAgentBody<>("Synthesize.", "Return JSON.", null), null);

        assertEquals(1, model.requests.size());
        assertEquals(1, logs.messages.size());
        assertTrue(logs.messages.getFirst().contains("PROMPT Return the result."));
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
        return factory(laneId, model, null);
    }

    private static LlmAgentFactory.DynamicAgentFactory factory(
            String laneId,
            ChatModel model,
            LlmRouteSupport routeSupport) {
        return FrameworkFixtures.agentFactory(
                laneId, model, new McpClientFactory(), new TestTokenCountEstimator(), 12000, 4, routeSupport);
    }

    private static LlmRouteSupport routeSupport(LogTokens logs) {
        DefaultCamelContext context = new DefaultCamelContext();
        return new LlmRouteSupport(
                context,
                new InFlightReplyManager(),
                new LlmDeadLetterFactory(),
                logs);
    }

    private static Exchange exchange(String laneId, LlmPassType passType) {
        return FrameworkFixtures.agentExchange(laneId, passType);
    }

    private static LlmJobEnvelope job(String laneId, LlmPassType passType, String schema) {
        return FrameworkFixtures.queuedJob(new LlmJobEnvelope(
                UUID.randomUUID(),
                laneId,
                passType,
                "test",
                "test",
                0,
                "Return the result.",
                "Return only JSON.",
                schema,
                Map.of("personaId", FrameworkFixtures.PERSONA_A, "llmForceProviderSchema", schema != null),
                null));
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

    private static final class CapturingLogTokens extends LogTokens {
        private final List<String> messages = new ArrayList<>();

        private CapturingLogTokens() {
            super(false);
        }

        @Override
        public void print(String message) {
            messages.add(message);
        }
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
