package us.nusun.llmshare.framework.tests.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.llm.InFlightReplyManager;
import us.nusun.llmshare.framework.llm.LlmAgentFactory;
import us.nusun.llmshare.framework.llm.LlmDeadLetterFactory;
import us.nusun.llmshare.framework.llm.LlmJobFactory;
import us.nusun.llmshare.framework.llm.LlmRouteSupport;
import us.nusun.llmshare.framework.mcp.McpClientFactory;
import us.nusun.llmshare.framework.memory.ApproximateTokenCountEstimator;
import us.nusun.llmshare.framework.model.LlmJobEnvelope;
import us.nusun.llmshare.framework.model.LlmJobRequest;
import us.nusun.llmshare.framework.model.LlmJobResult;
import us.nusun.llmshare.framework.model.LlmJobStatus;
import us.nusun.llmshare.framework.model.LlmPassType;
import us.nusun.llmshare.framework.route.LlmRoute;
import us.nusun.llmshare.framework.route.TerminalToolRoute;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;
import us.nusun.llmshare.framework.tests.support.StubKafkaComponent;
import us.nusun.llmshare.framework.tests.support.TestCamelContexts;
import us.nusun.llmshare.framework.tests.support.TestJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SinglePassCamelIntegrationTest {
    @Test
    void kafkaWorkerComposesTaggedCamelTerminalToolIntoAgentLoop() throws Exception {
        TerminalChatModel model = new TerminalChatModel();
        StubKafkaComponent kafka = new StubKafkaComponent();

        try (DefaultCamelContext context = routeContext(model, kafka)) {
            context.start();
            LlmJobEnvelope job = LlmJobEnvelope.queued(new LlmJobRequest(
                    UUID.randomUUID(),
                    "low-pass",
                    LlmPassType.TOOL,
                    "act:test",
                    "test",
                    0,
                    "Inspect repository state and finish the act.",
                    "Use tools and call finish_act when complete.",
                    null,
                    Map.of(
                            "personaId", FrameworkFixtures.PERSONA_A,
                            "toolProfile", "none",
                            "laneId", "low-pass",
                            "passType", "TOOL",
                            "camelToolTags", TerminalToolRoute.TAG),
                    null).normalized());

            kafka.emit("llm.requests.low", TestJson.write(job));

            await(() -> kafka.sentTopics().contains("llm.replies.low"));
            assertFalse(kafka.sentTopics().contains("llm.dead-letter"));
            Exchange reply = kafka.sentTo("llm.replies.low").getFirst();
            LlmJobResult result = TestJson.read(reply.getIn().getBody(String.class), LlmJobResult.class);
            assertEquals(LlmJobStatus.SUCCEEDED, result.status());
            assertEquals("{\"summary\":\"repository verified\",\"content\":\"repository is current\"}", result.content());
            assertEquals(2, model.requests.size());
            ToolExecutionResultMessage terminalResult = model.requests.get(1).messages().stream()
                    .filter(ToolExecutionResultMessage.class::isInstance)
                    .map(ToolExecutionResultMessage.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertEquals("finish_act", terminalResult.toolName());
            assertTrue(terminalResult.text().contains("repository verified"));
            assertTrue(terminalResult.text().contains("repository is current"));
        }
    }

    private static DefaultCamelContext routeContext(ChatModel model, StubKafkaComponent kafka) throws Exception {
        DefaultCamelContext context = TestCamelContexts.quiet();
        context.setAutowiredEnabled(false);
        context.getShutdownStrategy().setTimeout(1);
        Properties properties = new Properties();
        properties.setProperty("llm.consumer.group", "single-pass-test");
        properties.setProperty("llm.response.consumer.group", "single-pass-test-response");
        properties.setProperty("llm.model-default.openai.model", "test-model");
        properties.setProperty("llm.reply-timeout-ms", "100");
        properties.setProperty("llm.topic.dead-letter", "llm.dead-letter");
        context.getPropertiesComponent().setOverrideProperties(properties);
        context.addComponent("kafka", kafka);
        InFlightReplyManager replies = new InFlightReplyManager();
        context.getRegistry().bind("inFlightReplyManager", replies);
        LlmAgentFactory.DynamicAgentFactory agentFactory = new LlmAgentFactory.DynamicAgentFactory(
                FrameworkFixtures.inferenceLaneRegistry(),
                Map.of("low-pass", model),
                new McpClientFactory(),
                FrameworkFixtures.toolProfileRegistry(),
                FrameworkFixtures.personaCatalog(),
                new ApproximateTokenCountEstimator(),
                12000);
        agentFactory.setCamelContext(context);
        context.getRegistry().bind("dynamicAgentFactory", agentFactory);
        FrameworkFixtures.bindCatalogBeans(context);

        TerminalToolRoute terminal = new TerminalToolRoute();
        FrameworkFixtures.set(terminal, "objectMapper", new ObjectMapper());
        context.addRoutes(terminal);

        LlmRoute route = new LlmRoute();
        FrameworkFixtures.set(route, "lanes", FrameworkFixtures.inferenceLaneRegistry());
        FrameworkFixtures.set(route, "jobFactory", new LlmJobFactory(FrameworkFixtures.inferenceLaneRegistry(), FrameworkFixtures.personaCatalog()));
        LlmDeadLetterFactory deadLetters = new LlmDeadLetterFactory();
        FrameworkFixtures.set(route, "deadLetterFactory", deadLetters);
        FrameworkFixtures.set(route, "routeSupport", new LlmRouteSupport(context, FrameworkFixtures.inferenceLaneRegistry(), replies, deadLetters));
        context.addRoutes(route);
        return context;
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 1000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean());
    }

    private static final class TerminalChatModel implements ChatModel {
        private final List<ChatRequest> requests = new ArrayList<>();

        @Override
        public ChatResponse doChat(ChatRequest request) {
            requests.add(request);
            if (requests.size() == 1) {
                return ChatResponse.builder()
                        .aiMessage(new AiMessage(List.of(ToolExecutionRequest.builder()
                                .id("finish-1")
                                .name("finish_act")
                                .arguments("{\"summary\":\"repository verified\",\"content\":\"repository is current\"}")
                                .build())))
                        .build();
            }
            return ChatResponse.builder()
                    .aiMessage(new AiMessage("{\"summary\":\"repository verified\",\"content\":\"repository is current\"}"))
                    .build();
        }
    }
}
