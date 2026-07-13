package us.nusun.llmshare.framework.tests.component;

import us.nusun.llmshare.framework.llm.HallucinatedToolNameRecoveryStrategy;
import us.nusun.llmshare.framework.llm.InFlightReplyManager;
import us.nusun.llmshare.framework.llm.LlmAgentFactory;
import us.nusun.llmshare.framework.llm.LlmDeadLetterFactory;
import us.nusun.llmshare.framework.llm.LlmJobFactory;
import us.nusun.llmshare.framework.llm.LlmRouteSupport;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
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
import us.nusun.llmshare.framework.model.LlmJobEnvelope;
import us.nusun.llmshare.framework.model.LlmJobRequest;
import us.nusun.llmshare.framework.model.LlmJobResult;
import us.nusun.llmshare.framework.model.LlmJobStatus;
import us.nusun.llmshare.framework.model.LlmPassType;
import us.nusun.llmshare.framework.mcp.McpClientFactory;
import us.nusun.llmshare.framework.memory.ApproximateTokenCountEstimator;
import us.nusun.llmshare.framework.route.LlmRoute;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;
import us.nusun.llmshare.framework.tests.support.StubKafkaComponent;
import us.nusun.llmshare.framework.tests.support.TestCamelContexts;
import us.nusun.llmshare.framework.tests.support.TestJson;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HallucinatedToolNameRecoveryStrategyTest {
    private static final String HALLUCINATED_TOOL = "list_project_members";

    @Test
    void recoversFromOneHallucinatedToolName() {
        ScriptedChatModel model = ScriptedChatModel.hallucinateThenAnswer();
        Agent agent = factory("low-pass", model).createAgent(exchange("low-pass"));

        Result<String> result = assertDoesNotThrow(() -> agent.chat(
                new AiAgentBody<>("Inspect project state.", "You are a tester.", "memory-hallucination"),
                probeToolProvider()));

        assertEquals("final response", result.content());
        assertEquals(2, model.requests.size());
        ToolExecutionResultMessage toolResult = onlyToolResult(model.requests.get(1));
        assertEquals("call-1", toolResult.id());
        assertEquals(HALLUCINATED_TOOL, toolResult.toolName());
        assertTrue(toolResult.text().contains("No tool named '" + HALLUCINATED_TOOL + "' is available"));
        assertTrue(toolResult.text().contains("Do not retry this exact tool name"));
    }

    @Test
    void strategyPreservesRequestMetadataInSyntheticToolResult() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-42")
                .name(HALLUCINATED_TOOL)
                .arguments("{}")
                .build();

        ToolExecutionResultMessage result = new HallucinatedToolNameRecoveryStrategy().apply(request);

        assertEquals("call-42", result.id());
        assertEquals(HALLUCINATED_TOOL, result.toolName());
        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(result.text().contains("No tool named '" + HALLUCINATED_TOOL + "' is available in the current tool profile."));
        assertTrue(result.text().contains("Use only the currently available tools"));
    }

    @Test
    void camelAgentWorkerRouteDoesNotFailOrPublishDlqOnHallucinatedToolName() throws Exception {
        ScriptedChatModel model = ScriptedChatModel.hallucinateThenAnswer();
        StubKafkaComponent kafka = new StubKafkaComponent();

        try (DefaultCamelContext context = routeContext(model, kafka)) {
            context.start();
            LlmJobEnvelope job = LlmJobEnvelope.queued(new LlmJobRequest(
                    UUID.randomUUID(),
                    "low-pass",
                    LlmPassType.TOOL,
                    "test",
                    "test",
                    0,
                    "Inspect current state.",
                    "You are a tester.",
                    null,
                    Map.of(
                            "personaId", FrameworkFixtures.PERSONA_A,
                            "toolProfile", "none",
                            "laneId", "low-pass",
                            "passType", "TOOL"),
                    null).normalized());
            String json = TestJson.write(job);

            assertDoesNotThrow(() -> kafka.emit("llm.requests.low", json));

            await(() -> kafka.sentTopics().contains("llm.replies.low"));
            assertFalse(kafka.sentTopics().contains("llm.dead-letter"));
            Exchange replyExchange = kafka.sentTo("llm.replies.low").getFirst();
            assertFalse(replyExchange.isFailed());
            LlmJobResult result = TestJson.read(replyExchange.getIn().getBody(String.class), LlmJobResult.class);
            assertNotNull(result);
            assertEquals(LlmJobStatus.SUCCEEDED, result.status());
            assertEquals("final response", result.content());
        }
    }

    @Test
    void repeatedHallucinatedToolNamesRemainBoundedByRoundTripLimit() {
        ScriptedChatModel model = ScriptedChatModel.alwaysHallucinates();
        Agent agent = factory("low-pass", model).createAgent(exchange("low-pass"));

        RuntimeException failure = assertThrows(RuntimeException.class, () -> agent.chat(
                new AiAgentBody<>("Inspect project state.", "You are a tester.", "memory-repeated"),
                probeToolProvider()));

        assertTrue(model.requests.size() > 1);
        assertTrue(model.requests.size() <= 6);
        assertTrue(failure.getMessage().contains("exceeded 4 tool calling round trips"));
    }

    @Test
    void realToolExecutionFailuresUseExistingToolErrorBehavior() {
        ScriptedChatModel model = ScriptedChatModel.requestFailingToolThenAnswer();
        Agent agent = factory("low-pass", model).createAgent(exchange("low-pass"));

        Result<String> result = assertDoesNotThrow(() -> agent.chat(
                new AiAgentBody<>("Run failing tool.", "You are a tester.", "memory-tool-failure"),
                failingToolProvider()));

        assertEquals("final response", result.content());
        assertEquals(2, model.requests.size());
        ToolExecutionResultMessage toolResult = onlyToolResult(model.requests.get(1));
        assertEquals("failing_tool", toolResult.toolName());
        assertTrue(toolResult.text().contains("tool transport failed"));
        assertFalse(toolResult.text().contains("No tool named"));
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

    private static Exchange exchange(String laneId) {
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.setProperty("laneId", laneId);
        exchange.setProperty("passType", LlmPassType.TOOL);
        exchange.setProperty("personaId", FrameworkFixtures.PERSONA_A);
        exchange.setProperty("toolProfile", "none");
        return exchange;
    }

    private static DefaultCamelContext routeContext(ChatModel model, StubKafkaComponent kafka) throws Exception {
        DefaultCamelContext context = TestCamelContexts.quiet();
        context.setAutowiredEnabled(false);
        context.getShutdownStrategy().setTimeout(1);
        Properties properties = new Properties();
        properties.setProperty("llm.consumer.group", "hallucinated-tool-test");
        properties.setProperty("llm.response.consumer.group", "hallucinated-tool-test-response");
        properties.setProperty("llm.model-default.openai.model", "test-model");
        properties.setProperty("llm.reply-timeout-ms", "100");
        properties.setProperty("llm.topic.dead-letter", "llm.dead-letter");
        context.getPropertiesComponent().setOverrideProperties(properties);
        context.addComponent("kafka", kafka);
        InFlightReplyManager replies = new InFlightReplyManager();
        context.getRegistry().bind("inFlightReplyManager", replies);
        LlmAgentFactory.DynamicAgentFactory agentFactory = factory("low-pass", model);
        agentFactory.setCamelContext(context);
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

    private static ToolProvider probeToolProvider() {
        ToolSpecification tool = ToolSpecification.builder()
                .name("probe_tool")
                .description("Probe external state")
                .build();
        return request -> ToolProviderResult.builder()
                .add(tool, (toolRequest, memoryId) -> "tool result")
                .build();
    }

    private static ToolProvider failingToolProvider() {
        ToolSpecification tool = ToolSpecification.builder()
                .name("failing_tool")
                .description("Fails during execution")
                .build();
        return request -> ToolProviderResult.builder()
                .add(tool, (toolRequest, memoryId) -> {
                    throw new IllegalStateException("tool transport failed");
                })
                .build();
    }

    private static ToolExecutionResultMessage onlyToolResult(ChatRequest request) {
        return request.messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static final class ScriptedChatModel implements ChatModel {
        private enum Mode { HALLUCINATE_THEN_ANSWER, ALWAYS_HALLUCINATES, FAILING_TOOL_THEN_ANSWER }

        private final Mode mode;
        private final List<ChatRequest> requests = new ArrayList<>();

        private ScriptedChatModel(Mode mode) {
            this.mode = mode;
        }

        static ScriptedChatModel hallucinateThenAnswer() {
            return new ScriptedChatModel(Mode.HALLUCINATE_THEN_ANSWER);
        }

        static ScriptedChatModel alwaysHallucinates() {
            return new ScriptedChatModel(Mode.ALWAYS_HALLUCINATES);
        }

        static ScriptedChatModel requestFailingToolThenAnswer() {
            return new ScriptedChatModel(Mode.FAILING_TOOL_THEN_ANSWER);
        }

        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            requests.add(chatRequest);
            return switch (mode) {
                case HALLUCINATE_THEN_ANSWER -> requests.size() == 1
                        ? toolRequest("call-1", HALLUCINATED_TOOL)
                        : finalAnswer();
                case ALWAYS_HALLUCINATES -> toolRequest("call-" + requests.size(), HALLUCINATED_TOOL);
                case FAILING_TOOL_THEN_ANSWER -> requests.size() == 1
                        ? toolRequest("call-failing", "failing_tool")
                        : finalAnswer();
            };
        }

        private static ChatResponse toolRequest(String id, String name) {
            return ChatResponse.builder()
                    .aiMessage(new AiMessage(List.of(ToolExecutionRequest.builder()
                            .id(id)
                            .name(name)
                            .arguments("{}")
                            .build())))
                    .build();
        }

        private static ChatResponse finalAnswer() {
            return ChatResponse.builder()
                    .aiMessage(new AiMessage("final response"))
                    .build();
        }
    }
}
