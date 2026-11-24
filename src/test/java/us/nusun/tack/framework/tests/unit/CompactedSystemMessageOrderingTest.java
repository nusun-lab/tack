package us.nusun.tack.framework.tests.unit;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.apache.camel.Exchange;
import org.apache.camel.component.langchain4j.agent.api.Agent;
import org.apache.camel.component.langchain4j.agent.api.AiAgentBody;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import us.nusun.tack.framework.llm.LlmAgentFactory;
import us.nusun.tack.framework.mcp.McpClientFactory;
import us.nusun.tack.framework.model.LlmPassType;
import us.nusun.tack.framework.tests.support.FrameworkFixtures;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactedSystemMessageOrderingTest {

    @Test
    void compactedToolPassKeepsExactlyOneSystemMessageAndKeepsItFirst() {
        String property = "llm.inference-lane.low.tool-memory.max-tokens";
        String previous = System.getProperty(property);
        System.setProperty(property, "35");
        try {
            RecordingToolLoopModel model = new RecordingToolLoopModel(2);
            Agent agent = factory(model).createAgent(exchange());

            agent.chat(new AiAgentBody<>(
                    "Inspect current external state until enough evidence is available.",
                    "You are a tester. The system message must remain first.",
                    "compaction-ordering-regression"), probeToolProvider());

            assertTrue(model.requests.size() >= 2, "expected at least one post-tool model request");

            ChatRequest compactedRequest = model.requests.stream()
                    .skip(1)
                    .filter(CompactedSystemMessageOrderingTest::containsCompactionDegradationWarning)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "expected a post-tool request produced from compacted tool-pass memory"));

            List<ChatMessage> messages = compactedRequest.messages();
            long systemMessages = messages.stream().filter(SystemMessage.class::isInstance).count();

            assertEquals(1, systemMessages,
                    () -> "compaction must not add a second system message: " + messageTypes(messages));
            assertInstanceOf(SystemMessage.class, messages.getFirst(),
                    () -> "system message must remain the first outbound message after compaction: " + messageTypes(messages));
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    private static boolean containsCompactionDegradationWarning(ChatRequest request) {
        return request.messages().stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .anyMatch(message -> "tack-runtime".equals(message.name())
                        && message.singleText().contains("Older messages are being replaced with lower-fidelity summaries"));
    }

    private static String messageTypes(List<ChatMessage> messages) {
        return messages.stream().map(message -> message.getClass().getSimpleName()).toList().toString();
    }

    private static LlmAgentFactory.DynamicAgentFactory factory(ChatModel model) {
        return new LlmAgentFactory.DynamicAgentFactory(
                FrameworkFixtures.inferenceLaneRegistry(),
                Map.of("low-pass", model),
                new McpClientFactory(),
                FrameworkFixtures.toolProfileRegistry(),
                FrameworkFixtures.personaCatalog(),
                new CompactionTokenEstimator(),
                35,
                4);
    }

    private static Exchange exchange() {
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.setProperty("laneId", "low-pass");
        exchange.setProperty("passType", LlmPassType.ACTION);
        exchange.setProperty("personaId", FrameworkFixtures.PERSONA_A);
        exchange.setProperty("toolProfile", "none");
        return exchange;
    }

    private static ToolProvider probeToolProvider() {
        ToolSpecification tool = ToolSpecification.builder()
                .name("probe_tool")
                .description("Return deterministic external state")
                .build();
        return request -> ToolProviderResult.builder()
                .add(tool, (toolRequest, memoryId) -> "deterministic tool result")
                .build();
    }

    private static final class RecordingToolLoopModel implements ChatModel {
        private final int toolCallsBeforeFinal;
        private final List<ChatRequest> requests = new ArrayList<>();

        private RecordingToolLoopModel(int toolCallsBeforeFinal) {
            this.toolCallsBeforeFinal = toolCallsBeforeFinal;
        }

        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            requests.add(chatRequest);
            if (requests.size() <= toolCallsBeforeFinal) {
                String id = "call-" + requests.size();
                return ChatResponse.builder()
                        .aiMessage(new AiMessage(List.of(ToolExecutionRequest.builder()
                                .id(id)
                                .name("probe_tool")
                                .arguments("{\"call\":" + requests.size() + "}")
                                .build())))
                        .build();
            }
            return ChatResponse.builder()
                    .aiMessage(new AiMessage("final response"))
                    .build();
        }
    }

    private static final class CompactionTokenEstimator implements TokenCountEstimator {
        @Override
        public int estimateTokenCountInMessage(ChatMessage message) {
            if (message instanceof SystemMessage || message instanceof UserMessage) {
                return 1;
            }
            if (message instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests()) {
                return 14;
            }
            if (message instanceof ToolExecutionResultMessage) {
                return 14;
            }
            return 3;
        }

        @Override
        public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
            int total = 0;
            for (ChatMessage message : messages) {
                total += estimateTokenCountInMessage(message);
            }
            return total;
        }

        @Override
        public int estimateTokenCountInText(String text) {
            return text == null || text.isBlank() ? 0 : Math.max(1, text.length() / 4);
        }
    }
}
