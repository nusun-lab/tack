package us.nusun.llmshare.framework.tests.unit;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.apache.camel.Exchange;
import org.apache.camel.component.langchain4j.agent.api.Agent;
import org.apache.camel.component.langchain4j.agent.api.AiAgentBody;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.llm.LlmAgentFactory;
import us.nusun.llmshare.framework.llm.LlmRouteSupport;
import us.nusun.llmshare.framework.mcp.McpClientFactory;
import us.nusun.llmshare.framework.memory.ApproximateTokenCountEstimator;
import us.nusun.llmshare.framework.model.LlmJob;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForcedToolChoiceTest {
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
    void actAgentMakesMultipleToolCallsAndReturnsFinishActWithoutAnotherModelRound() {
        RecordingChatModel model = new RecordingChatModel();
        Agent agent = factory("low-pass", model).createAgent(exchange("low-pass", actJob("low-pass", null)));
        AtomicInteger probeCalls = new AtomicInteger();

        Result<String> result = agent.chat(
                new AiAgentBody<>("Inspect current external state.", "Finish with finish_act.", "memory-1"),
                actTools(probeCalls));

        assertNull(result.content());
        Exchange terminalExchange = exchange("low-pass", actJob("low-pass", null));
        terminalExchange.getMessage().setHeader(
                org.apache.camel.component.langchain4j.agent.api.Headers.TOOL_EXECUTIONS,
                result.toolExecutions());
        assertEquals(
                "{\"jobId\":\"job-1\",\"summary\":\"done\",\"content\":\"durable\"}",
                new LlmRouteSupport().extractAgentResult(
                        null, terminalExchange.getProperty("llmJob", LlmJob.class), terminalExchange));
        assertEquals(3, model.requests.size());
        assertEquals(2, probeCalls.get());
        assertTrue(model.requests.stream().allMatch(request -> request.toolChoice() == ToolChoice.REQUIRED));
        assertEquals(2, model.requests.get(2).messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .count());
    }

    @Test
    void coEmittedFinishActWithNormalToolProducesDuplicateTerminalExecution() {
        CoEmittedFinishChatModel model = new CoEmittedFinishChatModel();
        Agent agent = factory("low-pass", model).createAgent(exchange("low-pass", actJob("low-pass", null)));
        AtomicInteger probeCalls = new AtomicInteger();

        Result<String> result = agent.chat(
                new AiAgentBody<>("Inspect current external state.", "Finish with finish_act.", "memory-2"),
                actTools(probeCalls));

        assertNull(result.content());
        assertEquals(2, model.requests.size());
        assertEquals(1, probeCalls.get());
        assertEquals(2, result.toolExecutions().stream()
                .filter(execution -> "finish_act".equals(execution.request().name()))
                .count());

        Exchange terminalExchange = exchange("low-pass", actJob("low-pass", null));
        terminalExchange.getMessage().setHeader(
                org.apache.camel.component.langchain4j.agent.api.Headers.TOOL_EXECUTIONS,
                result.toolExecutions());

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new LlmRouteSupport().extractAgentResult(
                        null, terminalExchange.getProperty("llmJob", LlmJob.class), terminalExchange));
        assertEquals("Act agent must call finish_act exactly once; observed 2", failure.getMessage());
    }

    @Test
    void structuredAgentStillUsesProviderResponseFormatWithoutTools() {
        RecordingChatModel model = new RecordingChatModel();
        Exchange exchange = exchange("low-pass", structuredJob("low-pass", SCHEMA), context(true));
        Agent agent = factory("low-pass", model).createAgent(exchange);

        agent.chat(new AiAgentBody<>("Synthesize.", "Return JSON.", null), null);

        assertEquals(1, model.requests.size());
        assertNull(model.requests.get(0).toolChoice());
        assertNotNull(model.requests.get(0).responseFormat());
        assertEquals(ResponseFormatType.JSON, model.requests.get(0).responseFormat().type());
        assertNotNull(model.requests.get(0).responseFormat().jsonSchema());
    }

    @Test
    void actRejectsProviderStructuredOutput() {
        RecordingChatModel model = new RecordingChatModel();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> factory("low-pass", model).createAgent(exchange("low-pass", actJob("low-pass", SCHEMA), context(true))));

        assertEquals("Tack act must not use provider structured output", failure.getMessage());
    }

    private static LlmAgentFactory.DynamicAgentFactory factory(String laneId, ChatModel model) {
        return new LlmAgentFactory.DynamicAgentFactory(
                FrameworkFixtures.inferenceLaneRegistry(),
                Map.of(laneId, model),
                new McpClientFactory(),
                FrameworkFixtures.toolProfileRegistry(),
                FrameworkFixtures.personaCatalog(),
                new ApproximateTokenCountEstimator(),
                12000);
    }

    private static Exchange exchange(String laneId, LlmJob job) {
        return exchange(laneId, job, new DefaultCamelContext());
    }

    private static Exchange exchange(String laneId, LlmJob job, DefaultCamelContext context) {
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("laneId", laneId);
        exchange.setProperty("personaId", FrameworkFixtures.PERSONA_A);
        exchange.setProperty("toolProfile", "none");
        exchange.setProperty("llmJob", job);
        return exchange;
    }

    private static DefaultCamelContext context(boolean providerSchemaEnabled) {
        DefaultCamelContext context = new DefaultCamelContext();
        Properties properties = new Properties();
        properties.setProperty("llm.model-default.structured-output.provider-schema-enabled", String.valueOf(providerSchemaEnabled));
        context.getPropertiesComponent().setOverrideProperties(properties);
        return context;
    }

    private static LlmJob actJob(String laneId, String schema) {
        return job(laneId, schema, Map.of("primitive", "act"));
    }

    private static LlmJob structuredJob(String laneId, String schema) {
        return job(laneId, schema, Map.of("primitive", "decide"));
    }

    private static LlmJob job(String laneId, String schema, Map<String, Object> metadata) {
        return new LlmJob(
                UUID.randomUUID(),
                laneId,
                "Return the result.",
                "Return only JSON.",
                schema,
                metadata,
                java.time.Instant.now());
    }

    private static ToolProvider actTools(AtomicInteger probeCalls) {
        ToolSpecification probe = ToolSpecification.builder()
                .name("probe_tool")
                .description("Probe external state")
                .build();
        ToolSpecification finish = ToolSpecification.builder()
                .name("finish_act")
                .description("Finish the act")
                .build();
        return request -> ToolProviderResult.builder()
                .add(probe, (toolRequest, memoryId) -> {
                    probeCalls.incrementAndGet();
                    return "tool result";
                })
                .add(finish, (toolRequest, memoryId) ->
                        "{\"jobId\":\"job-1\",\"summary\":\"done\",\"content\":\"durable\"}")
                .build();
    }

    private static final class RecordingChatModel implements ChatModel {
        private final List<ChatRequest> requests = new ArrayList<>();

        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            requests.add(chatRequest);
            if (chatRequest.toolSpecifications() == null || chatRequest.toolSpecifications().isEmpty()) {
                return ChatResponse.builder().aiMessage(AiMessage.from("{\"result\":\"ok\"}")).build();
            }
            String tool = requests.size() < 3 ? "probe_tool" : "finish_act";
            return ChatResponse.builder()
                    .aiMessage(new AiMessage(List.of(ToolExecutionRequest.builder()
                            .id("call-" + requests.size())
                            .name(tool)
                            .arguments(tool.equals("finish_act")
                                    ? "{\"summary\":\"done\",\"content\":\"durable\"}"
                                    : "{}")
                            .build())))
                    .build();
        }
    }

    private static final class CoEmittedFinishChatModel implements ChatModel {
        private final List<ChatRequest> requests = new ArrayList<>();

        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            requests.add(chatRequest);
            if (requests.size() == 1) {
                return ChatResponse.builder()
                        .aiMessage(new AiMessage(List.of(
                                toolCall("call-probe-1", "probe_tool", "{}"),
                                toolCall("call-finish-1", "finish_act", finishArguments()))))
                        .build();
            }
            return ChatResponse.builder()
                    .aiMessage(new AiMessage(List.of(
                            toolCall("call-finish-2", "finish_act", finishArguments()))))
                    .build();
        }

        private static ToolExecutionRequest toolCall(String id, String name, String arguments) {
            return ToolExecutionRequest.builder()
                    .id(id)
                    .name(name)
                    .arguments(arguments)
                    .build();
        }

        private static String finishArguments() {
            return "{\"summary\":\"done\",\"content\":\"durable\"}";
        }
    }
}
