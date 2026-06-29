package us.nusun.llmshare.framework.tests.unit;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.memory.ApproximateTokenCountEstimator;
import us.nusun.llmshare.framework.memory.EphemeralToolPassChatMemoryProvider;
import us.nusun.llmshare.framework.memory.ToolAwareTokenWindowChatMemory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolAwareMemoryTest {

    @Test
    void approximateEstimatorCountsCommonMessageTypes() {
        ApproximateTokenCountEstimator estimator = new ApproximateTokenCountEstimator();
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("search-1")
                .name("search")
                .arguments("{\"query\":\"parking lot manager\"}")
                .build();

        assertEquals(0, estimator.estimateTokenCountInText(null));
        assertEquals(0, estimator.estimateTokenCountInText("   "));
        assertEquals(1, estimator.estimateTokenCountInText("abcd"));
        assertTrue(estimator.estimateTokenCountInMessage(SystemMessage.from("system rules")) > 4);
        assertTrue(estimator.estimateTokenCountInMessage(UserMessage.from("requester", "build app")) > 4);
        assertTrue(estimator.estimateTokenCountInMessage(AiMessage.from(request)) > 12);
        assertTrue(estimator.estimateTokenCountInMessage(new ToolExecutionResultMessage("search-1", "search", "result")) > 4);
        assertTrue(estimator.estimateTokenCountInMessages(List.of(SystemMessage.from("system"), UserMessage.from("user"))) > 8);
    }

    @Test
    void providerCreatesEphemeralMemoryForRequestedId() {
        EphemeralToolPassChatMemoryProvider provider = new EphemeralToolPassChatMemoryProvider(
                20,
                new FixedTokenCountEstimator(),
                true);

        ChatMemory memory = provider.get("agent-run-1");

        assertEquals("agent-run-1", memory.id());
        memory.add(UserMessage.from("hello"));
        assertEquals(List.of(UserMessage.from("hello")), memory.messages());
    }

    @Test
    void systemMessageIsReplacedAndKeptFirstWhenConfigured() {
        ToolAwareTokenWindowChatMemory memory = ToolAwareTokenWindowChatMemory.builder()
                .id("agent-run-1")
                .maxTokens(20, new FixedTokenCountEstimator())
                .alwaysKeepSystemMessageFirst(true)
                .build();

        memory.add(UserMessage.from("mission"));
        memory.add(SystemMessage.from("old system"));
        memory.add(SystemMessage.from("new system"));
        memory.add(AiMessage.from("response"));

        assertEquals(List.of(
                SystemMessage.from("new system"),
                UserMessage.from("mission"),
                AiMessage.from("response")), memory.messages());
    }

    @Test
    void toolRequestEvictionAlsoRemovesOrphanToolResults() {
        ToolAwareTokenWindowChatMemory memory = ToolAwareTokenWindowChatMemory.builder()
                .id("agent-run-1")
                .maxTokens(25, new FixedTokenCountEstimator())
                .alwaysKeepSystemMessageFirst(true)
                .build();
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("tool-1")
                .name("search")
                .arguments("{}")
                .build();

        memory.add(SystemMessage.from("system"));
        memory.add(UserMessage.from("original user stays protected"));
        memory.add(AiMessage.from(request));
        memory.add(new ToolExecutionResultMessage("tool-1", "search", "tool result"));
        memory.add(AiMessage.from("ordinary answer"));
        memory.add(UserMessage.from("follow up"));

        assertEquals(List.of(
                SystemMessage.from("system"),
                UserMessage.from("original user stays protected"),
                AiMessage.from("ordinary answer"),
                UserMessage.from("follow up")), memory.messages());
    }

    @Test
    void messagesReturnsDefensiveSnapshot() {
        ToolAwareTokenWindowChatMemory memory = ToolAwareTokenWindowChatMemory.builder()
                .id("agent-run-1")
                .maxTokens(20, new FixedTokenCountEstimator())
                .build();
        memory.add(UserMessage.from("hello"));

        List<ChatMessage> snapshot = memory.messages();
        snapshot.clear();

        assertEquals(List.of(UserMessage.from("hello")), memory.messages());
    }

    @Test
    void setAppliesCapacityRules() {
        ToolAwareTokenWindowChatMemory memory = ToolAwareTokenWindowChatMemory.builder()
                .id("agent-run-1")
                .maxTokens(25, new FixedTokenCountEstimator())
                .alwaysKeepSystemMessageFirst(true)
                .build();
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("tool-1")
                .name("search")
                .arguments("{}")
                .build();

        memory.set(List.of(
                SystemMessage.from("system"),
                UserMessage.from("original user stays protected"),
                AiMessage.from(request),
                new ToolExecutionResultMessage("tool-1", "search", "tool result"),
                AiMessage.from("ordinary answer"),
                UserMessage.from("follow up")));

        assertEquals(List.of(
                SystemMessage.from("system"),
                UserMessage.from("original user stays protected"),
                AiMessage.from("ordinary answer"),
                UserMessage.from("follow up")), memory.messages());
    }

    @Test
    void throwsReadableErrorWhenOnlyProtectedMessagesRemainOverBudget() {
        ToolAwareTokenWindowChatMemory memory = ToolAwareTokenWindowChatMemory.builder()
                .id("agent-run-1")
                .maxTokens(1, new FixedTokenCountEstimator())
                .alwaysKeepSystemMessageFirst(true)
                .build();

        memory.add(SystemMessage.from("system"));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> memory.add(UserMessage.from("original user")));

        assertEquals(
                "Ephemeral tool-pass memory exceeded the safe token budget while only protected messages remain",
                failure.getMessage());
    }

    @Test
    void clearRemovesAllMessages() {
        ToolAwareTokenWindowChatMemory memory = ToolAwareTokenWindowChatMemory.builder()
                .id("agent-run-1")
                .maxTokens(20, new FixedTokenCountEstimator())
                .build();
        memory.add(UserMessage.from("hello"));

        memory.clear();

        assertTrue(memory.messages().isEmpty());
    }

    private static final class FixedTokenCountEstimator implements TokenCountEstimator {
        @Override
        public int estimateTokenCountInMessage(ChatMessage message) {
            if (message instanceof SystemMessage || message instanceof UserMessage) {
                return 1;
            }
            if (message instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests()) {
                return 10;
            }
            if (message instanceof ToolExecutionResultMessage) {
                return 10;
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
