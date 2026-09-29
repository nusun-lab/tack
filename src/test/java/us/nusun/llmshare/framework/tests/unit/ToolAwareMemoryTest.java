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
import us.nusun.llmshare.framework.memory.EphemeralToolPassChatMemoryProvider;
import us.nusun.llmshare.framework.memory.ToolAwareTokenWindowChatMemory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolAwareMemoryTest {

    @Test
    void providerCreatesEphemeralMemoryForRequestedId() {
        EphemeralToolPassChatMemoryProvider provider = new EphemeralToolPassChatMemoryProvider(
                200, new FixedTokenCountEstimator(), true, 12, 0.50, 3);

        ChatMemory memory = provider.get("agent-run-1");

        assertEquals("agent-run-1", memory.id());
        memory.add(UserMessage.from("hello"));
        assertEquals(List.of(UserMessage.from("hello")), memory.messages());
    }

    @Test
    void systemMessageIsReplacedAndKeptFirst() {
        ToolAwareTokenWindowChatMemory memory = memory(200, 12, 0.50, 3);

        memory.add(UserMessage.from("mission"));
        memory.add(SystemMessage.from("old system"));
        memory.add(SystemMessage.from("new system"));

        assertEquals(SystemMessage.from("new system"), memory.messages().getFirst());
        assertFalse(memory.messages().contains(SystemMessage.from("old system")));
    }

    @Test
    void belowCompactionThresholdDoesNotModifyModelContext() {
        List<String> warnings = new ArrayList<>();
        ToolAwareTokenWindowChatMemory memory = ToolAwareTokenWindowChatMemory.builder()
                .id("agent-run-1")
                .maxTokens(100, new FixedTokenCountEstimator())
                .alwaysKeepSystemMessageFirst(true)
                .maxToolCalls(4)
                .compactionThreshold(0.50)
                .recentToolInteractions(3)
                .runtimeWarningSink(warnings::add)
                .build();
        memory.add(SystemMessage.from("system"));
        memory.add(UserMessage.from("tool pass"));
        addExecution(memory, "1", "search", "{\"q\":\"seed\"}", "[]", false);

        List<ChatMessage> projected = memory.messages();
        String system = systemText(projected);

        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("TOKEN BUDGET transcript=22/100 (22%)"));
        assertTrue(warnings.getFirst().contains("calls=1/4"));
        assertFalse(system.contains("Runtime budget warning"));
        assertFalse(system.contains("1/4"));
        assertFalse(system.contains("remaining"));
        assertFalse(system.toLowerCase().contains("healthy"));
        assertFalse(projected.stream().anyMatch(ToolAwareMemoryTest::isLegacyRuntimeUserMessage));
        assertInstanceOf(ToolExecutionResultMessage.class, projected.getLast());
    }

    @Test
    void compactionUsesDeterministicJournalAndKeepsRecentInteraction() {
        ToolAwareTokenWindowChatMemory memory = memory(45, 12, 0.50, 1);
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
    void compactedContextLogReportsRawAndProjectedTranscript() {
        List<String> warnings = new ArrayList<>();
        ToolAwareTokenWindowChatMemory memory = ToolAwareTokenWindowChatMemory.builder()
                .id("agent-run-1")
                .maxTokens(45, new FixedTokenCountEstimator())
                .alwaysKeepSystemMessageFirst(true)
                .maxToolCalls(12)
                .compactionThreshold(0.50)
                .recentToolInteractions(1)
                .runtimeWarningSink(warnings::add)
                .build();
        memory.add(SystemMessage.from("system"));
        memory.add(UserMessage.from("tool pass"));
        addExecution(memory, "1", "search", "{}", "[]", false);
        addExecution(memory, "2", "lookup", "{}", "result", false);

        List<ChatMessage> projected = memory.messages();
        int projectedTokens = new FixedTokenCountEstimator().estimateTokenCountInMessages(projected);

        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("TOKEN BUDGET transcript=" + projectedTokens + "/45"));
        assertTrue(warnings.getFirst().contains("rawTranscript="));
        assertTrue(warnings.getFirst().contains("compacted=true"));
    }

    @Test
    void repeatedIdenticalExecutionIsLoggedButDoesNotModifyToolResult() {
        List<String> warnings = new ArrayList<>();
        ToolAwareTokenWindowChatMemory memory = ToolAwareTokenWindowChatMemory.builder()
                .id("agent-run-1")
                .maxTokens(1000, new FixedTokenCountEstimator())
                .alwaysKeepSystemMessageFirst(true)
                .maxToolCalls(8)
                .compactionThreshold(0.90)
                .recentToolInteractions(3)
                .runtimeWarningSink(warnings::add)
                .build();
        memory.add(SystemMessage.from("system"));
        memory.add(UserMessage.from("tool pass"));
        addExecution(memory, "1", "search", "{\"q\":\"butterfly-garden\"}", "[]", false);
        addExecution(memory, "2", "search", "{\"q\":\"butterfly-garden\"}", "[]", false);

        List<ChatMessage> projected = memory.messages();
        ToolExecutionResultMessage result = assertInstanceOf(ToolExecutionResultMessage.class, projected.getLast());

        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("repeats=1"));
        assertEquals("[]", result.text());
        assertFalse(projected.stream().anyMatch(ToolAwareMemoryTest::isLegacyRuntimeUserMessage));
    }

    @Test
    void differentExecutionClearsTrailingRepetitionLog() {
        List<String> warnings = new ArrayList<>();
        ToolAwareTokenWindowChatMemory memory = ToolAwareTokenWindowChatMemory.builder()
                .id("agent-run-1")
                .maxTokens(1000, new FixedTokenCountEstimator())
                .alwaysKeepSystemMessageFirst(true)
                .maxToolCalls(8)
                .compactionThreshold(0.90)
                .recentToolInteractions(3)
                .runtimeWarningSink(warnings::add)
                .build();
        memory.add(SystemMessage.from("system"));
        memory.add(UserMessage.from("tool pass"));
        addExecution(memory, "1", "search", "{\"q\":\"butterfly-garden\"}", "[]", false);
        addExecution(memory, "2", "search", "{\"q\":\"butterfly-garden\"}", "[]", false);
        memory.messages();
        warnings.clear();
        addExecution(memory, "3", "search", "{\"q\":\"butterfly\"}", "[]", false);

        List<ChatMessage> projected = memory.messages();
        ToolExecutionResultMessage result = assertInstanceOf(ToolExecutionResultMessage.class, projected.getLast());

        assertEquals(1, warnings.size());
        assertFalse(warnings.getFirst().contains("repeats="));
        assertEquals("[]", result.text());
    }

    @Test
    void everyToolRequestConsumesToolBudgetWithoutAdvertisingMaximum() {
        List<String> warnings = new ArrayList<>();
        ToolAwareTokenWindowChatMemory memory = ToolAwareTokenWindowChatMemory.builder()
                .id("agent-run-1")
                .maxTokens(1000, new FixedTokenCountEstimator())
                .alwaysKeepSystemMessageFirst(true)
                .maxToolCalls(4)
                .compactionThreshold(0.90)
                .recentToolInteractions(3)
                .runtimeWarningSink(warnings::add)
                .build();
        memory.add(SystemMessage.from("system"));
        memory.add(UserMessage.from("tool pass"));
        memory.add(AiMessage.from(List.of(
                request("1", "search", "{}"),
                request("2", "lookup", "{}"))));

        List<ChatMessage> projected = memory.messages();
        String system = systemText(projected);

        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("calls=2/4"));
        assertFalse(system.contains("Runtime budget warning"));
        assertFalse(system.contains("2/4"));
        assertFalse(projected.stream().anyMatch(ToolAwareMemoryTest::isLegacyRuntimeUserMessage));
        assertInstanceOf(AiMessage.class, projected.getLast());
    }

    @Test
    void clearRemovesHistoryJournalAndBudgetState() {
        ToolAwareTokenWindowChatMemory memory = memory(1000, 4, 0.50, 3);
        memory.add(SystemMessage.from("system"));
        memory.add(UserMessage.from("tool pass"));
        addExecution(memory, "1", "search", "{}", "[]", false);

        memory.clear();

        assertTrue(memory.messages().isEmpty());
    }

    private static ToolAwareTokenWindowChatMemory memory(
            int maxTokens, int maxToolCalls, double compactionThreshold, int recentInteractions) {
        return ToolAwareTokenWindowChatMemory.builder()
                .id("agent-run-1")
                .maxTokens(maxTokens, new FixedTokenCountEstimator())
                .alwaysKeepSystemMessageFirst(true)
                .maxToolCalls(maxToolCalls)
                .compactionThreshold(compactionThreshold)
                .recentToolInteractions(recentInteractions)
                .build();
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

    private static String systemText(List<ChatMessage> messages) {
        return messages.stream()
                .filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast)
                .map(SystemMessage::text)
                .findFirst()
                .orElse("");
    }

    private static boolean isLegacyRuntimeUserMessage(ChatMessage message) {
        return message instanceof UserMessage userMessage && "tack-runtime".equals(userMessage.name());
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
