package us.nusun.tack.framework.inference.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import org.junit.jupiter.api.Test;
import us.nusun.tack.framework.inference.lane.ToolPassSettings;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolAwareMemoryTest {

    @Test
    void providerCreatesEphemeralMemoryForRequestedId() {
        var settings = new ToolPassSettings(200, 12, 0.50, 3, 200, 32);
        ChatMemory memory = ToolLoopGuardChatMemory.forPass("agent-run-1", settings, new FixedTokenCountEstimator());

        assertEquals("agent-run-1", memory.id());
        memory.add(UserMessage.from("hello"));
        assertEquals(List.of(UserMessage.from("hello")), memory.messages());
        assertTrue(ToolLoopGuardChatMemory.forPass("agent-run-1", settings, new FixedTokenCountEstimator())
                .messages().isEmpty());
    }

    @Test
    void systemMessageIsReplacedAndKeptFirst() {
        ToolAwareTokenWindowChatMemory memory = memory(200, 0.50, 3);

        memory.add(UserMessage.from("mission"));
        memory.add(SystemMessage.from("old system"));
        memory.add(SystemMessage.from("new system"));

        assertEquals(SystemMessage.from("new system"), memory.messages().getFirst());
        assertFalse(memory.messages().contains(SystemMessage.from("old system")));
    }

    @Test
    void belowCompactionThresholdDoesNotModifyModelContext() {
        ToolAwareTokenWindowChatMemory memory = memory(100, 0.50, 3);
        memory.add(SystemMessage.from("system"));
        memory.add(UserMessage.from("tool pass"));
        addExecution(memory, "1", "search", "{\"q\":\"seed\"}", "[]", false);

        List<ChatMessage> projected = memory.messages();
        assertEquals(4, projected.size());
        assertEquals(SystemMessage.from("system"), projected.getFirst());
        assertEquals(UserMessage.from("tool pass"), projected.get(1));
        assertInstanceOf(AiMessage.class, projected.get(2));
        assertInstanceOf(ToolExecutionResultMessage.class, projected.getLast());
    }

    @Test
    void compactionUsesDeterministicJournalAndKeepsRecentInteraction() {
        ToolAwareTokenWindowChatMemory memory = memory(45, 0.50, 1);
        memory.add(SystemMessage.from("system"));
        memory.add(UserMessage.from("tool pass"));
        addExecution(memory, "1", "search", "{\"q\":\"seed\"}", "[]", false);
        addExecution(memory, "2", "create_repo", "{\"readme\":\"long\"}", "[Readme]: MaxSize", true);

        List<ChatMessage> projected = memory.messages();
        String journal = journalText(projected);

        assertTrue(journal.contains("Execution journal"));
        assertTrue(journal.contains("search({\"q\":\"seed\"})"));
        assertTrue(journal.contains("SUCCESS: []"));
        assertTrue(projected.stream().anyMatch(ToolAwareMemoryTest::isDegradationMessage));
        assertFalse(projected.contains(AiMessage.from(request("1", "search", "{\"q\":\"seed\"}"))));
        assertTrue(projected.contains(AiMessage.from(request("2", "create_repo", "{\"readme\":\"long\"}"))));
        assertInstanceOf(ToolExecutionResultMessage.class, projected.getLast());
    }

    @Test
    void compactionRetainsIncompleteRequestsInTheLatestToolGroup() {
        var memory = memory(60, 0.50, 1);
        memory.add(SystemMessage.from("system"));
        memory.add(UserMessage.from("tool pass"));
        addExecution(memory, "1", "search", "{}", "[]", false);
        var group = AiMessage.from(List.of(request("2", "lookup", "{}"), request("3", "inspect", "{}")));
        memory.add(group);

        List<ChatMessage> projected = memory.messages();
        assertEquals(group, projected.getLast());
        assertTrue(journalText(projected).contains("search"));
    }

    @Test
    void throwsWhenProtectedMessagesExceedTheHardBudget() {
        var memory = memory(1, 0.50, 1);
        memory.add(SystemMessage.from("system"));
        memory.add(UserMessage.from("tool pass"));

        assertThrows(IllegalStateException.class, memory::messages);
    }

    @Test
    void clearRemovesHistoryJournalAndBudgetState() {
        ToolAwareTokenWindowChatMemory memory = memory(1000, 0.50, 3);
        memory.add(SystemMessage.from("system"));
        memory.add(UserMessage.from("tool pass"));
        addExecution(memory, "1", "search", "{}", "[]", false);

        memory.clear();

        assertTrue(memory.messages().isEmpty());
    }

    private static ToolAwareTokenWindowChatMemory memory(int maxTokens, double threshold, int recentInteractions) {
        return new ToolAwareTokenWindowChatMemory("agent-run-1", maxTokens, new FixedTokenCountEstimator(),
                threshold, recentInteractions);
    }

    private static void addExecution(
            ChatMemory memory, String id, String name, String arguments, String result, boolean error) {
        ToolExecutionRequest request = request(id, name, arguments);
        memory.add(AiMessage.from(request));
        memory.add(ToolExecutionResultMessage.builder()
                .id(id)
                .toolName(name)
                .text(result)
                .isError(error)
                .build());
    }

    private static ToolExecutionRequest request(String id, String name, String arguments) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments(arguments).build();
    }

    private static boolean isDegradationMessage(ChatMessage message) {
        return message instanceof UserMessage userMessage
                && "tack-runtime".equals(userMessage.name())
                && userMessage.singleText().contains("Older messages are being replaced with lower-fidelity summaries");
    }

    private static String journalText(List<ChatMessage> messages) {
        return messages.stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .filter(message -> "execution-journal".equals(message.name()))
                .map(UserMessage::singleText)
                .findFirst()
                .orElse("");
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
