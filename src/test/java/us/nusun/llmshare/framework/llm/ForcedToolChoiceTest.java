package us.nusun.llmshare.framework.llm;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
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
import us.nusun.llmshare.framework.mcp.McpClientFactory;
import us.nusun.llmshare.framework.memory.ApproximateTokenCountEstimator;
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
        Agent agent = factory("tool-low", model).createAgent(exchange("tool-low"));

        agent.chat(new AiAgentBody<>("Inspect current external state.", "You are a tester.", "memory-1"), probeToolProvider());

        assertEquals(2, model.requests.size());
        assertEquals(ToolChoice.REQUIRED, model.requests.get(0).toolChoice());
        assertEquals(ToolChoice.AUTO, model.requests.get(1).toolChoice());
        assertTrue(model.requests.get(1).messages().stream().anyMatch(ToolExecutionResultMessage.class::isInstance));
    }

    @Test
    void toolEnabledAgentPreservesProviderResponseFormat() {
        RecordingChatModel model = new RecordingChatModel(false);
        Exchange exchange = exchange("tool-low", context(true));
        exchange.setProperty("llmJob", job("tool-low", SCHEMA));
        Agent agent = factory("tool-low", model).createAgent(exchange);

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
        Agent agent = factory("synthesis-low", model).createAgent(exchange("synthesis-low"));

        agent.chat(new AiAgentBody<>("Synthesize.", "Return JSON.", null), null);

        assertEquals(1, model.requests.size());
        assertNull(model.requests.get(0).toolChoice());
    }

    @Test
    void forcedToolChoiceTransformerPreservesExistingRequestFields() {
        List<ChatMessage> messages = List.of(SystemMessage.from("system"), UserMessage.from("user"));
        ToolSpecification tool = ToolSpecification.builder().name("probe_tool").description("Probe").build();
        ResponseFormat responseFormat = ResponseFormat.builder().type(ResponseFormatType.JSON).build();
        ChatRequestParameters parameters = ChatRequestParameters.builder()
                .modelName("model-a")
                .temperature(0.2)
                .topP(0.8)
                .topK(40)
                .frequencyPenalty(0.1)
                .presencePenalty(0.3)
                .maxOutputTokens(123)
                .stopSequences(List.of("STOP"))
                .toolSpecifications(List.of(tool))
                .toolChoice(ToolChoice.NONE)
                .responseFormat(responseFormat)
                .build();
        ChatRequest request = ChatRequest.builder()
                .messages(messages)
                .parameters(parameters)
                .build();

        ChatRequest transformed = LlmAgentFactory.ExecutionRuntimeAgentFactory.forceInitialToolChoice(request);

        assertEquals(messages, transformed.messages());
        assertEquals("model-a", transformed.modelName());
        assertEquals(0.2, transformed.temperature());
        assertEquals(0.8, transformed.topP());
        assertEquals(40, transformed.topK());
        assertEquals(0.1, transformed.frequencyPenalty());
        assertEquals(0.3, transformed.presencePenalty());
        assertEquals(123, transformed.maxOutputTokens());
        assertEquals(List.of("STOP"), transformed.stopSequences());
        assertEquals(List.of(tool), transformed.toolSpecifications());
        assertEquals(ResponseFormatType.JSON, transformed.responseFormat().type());
        assertEquals(ToolChoice.REQUIRED, transformed.toolChoice());
    }

    @Test
    void forcedToolChoiceTransformerUsesAutoWhenRequestAlreadyHasToolResult() {
        List<ChatMessage> messages = List.of(
                UserMessage.from("user"),
                new ToolExecutionResultMessage("call-1", "probe_tool", "result"));
        ChatRequest request = ChatRequest.builder()
                .messages(messages)
                .parameters(ChatRequestParameters.builder()
                        .toolSpecifications(List.of(ToolSpecification.builder().name("probe_tool").build()))
                        .build())
                .build();

        ChatRequest transformed = LlmAgentFactory.ExecutionRuntimeAgentFactory.forceInitialToolChoice(request);

        assertEquals(messages, transformed.messages());
        assertEquals(ToolChoice.AUTO, transformed.toolChoice());
    }

    private static LlmAgentFactory.ExecutionRuntimeAgentFactory factory(String runtimeId, ChatModel model) {
        return new LlmAgentFactory.ExecutionRuntimeAgentFactory(
                FrameworkFixtures.executionRuntimeRegistry(),
                Map.of(runtimeId, model),
                new McpClientFactory(),
                FrameworkFixtures.toolProfileRegistry(),
                FrameworkFixtures.personaCatalog(),
                new ApproximateTokenCountEstimator(),
                12000);
    }

    private static Exchange exchange(String runtimeId) {
        return exchange(runtimeId, new DefaultCamelContext());
    }

    private static Exchange exchange(String runtimeId, DefaultCamelContext context) {
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("executionRuntimeId", runtimeId);
        exchange.setProperty("personaId", FrameworkFixtures.PERSONA_A);
        exchange.setProperty("toolProfile", "none");
        return exchange;
    }

    private static DefaultCamelContext context(boolean providerSchemaEnabled) {
        DefaultCamelContext context = new DefaultCamelContext();
        Properties properties = new Properties();
        properties.setProperty("llm.runtime-default.structured-output.provider-schema-enabled", String.valueOf(providerSchemaEnabled));
        context.getPropertiesComponent().setOverrideProperties(properties);
        return context;
    }

    private static LlmJobEnvelope job(String runtimeId, String schema) {
        return LlmJobEnvelope.queued(new LlmJobRequest(
                UUID.randomUUID(),
                runtimeId,
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
