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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolAwareMemoryTest {

    @Test
    void approximateEstimatorCountsCommonMessageTypes() {
        ApproximateTokenCountEstimator estimator = new ApproximateTokenCountEstimator();
        ToolExecutionRequest request = request("search-1", "search", "{\"query\":\"parking lot manager\"}");

        assertEquals(0, estimator.estimateTokenCountInText(null));
        assertEquals(0, estimator.estimateTokenCountInText("   "));
        assertEquals(1, estimator.estimateTokenCountInText("abcd"));
        assertTrue(estimator.estimateTokenCountInMessage(SystemMessage.from("system rules")) > 4);
        assertTrue(estimator.estimateTokenCountInMessage(UserMessage.from("requester", "build app")) > 4);
        assertTrue(estimator.estimateTokenCountInMessage(AiMessage.from(request)) > 12);
        assertTrue(estimator.estimateTokenCountInMessage(new ToolExecutionResultMessage("search-1", "search", "result")) > 4);
    }

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
    void toolCallBudgetAwarenessAppearsAtQuarterBudgetWithoutCompaction() {
        ToolAwareTokenWindowChatMemory memory = memory(1000, 4, 0.50, 3);
        memory.add(SystemMessage.from("system"));
        memory.add(UserMessage.from("tool pass"));
        addExecution(memory, "1", "search", "{\"q\":\"seed\"}", "[]", false);

        List<ChatMessage> projected = memory.messages();
        String runtime = runtimeText(projected);

        assertTrue(runtime.contains("tool calls: 1/4 (25%)"));
        assertTrue(runtime.contains("budget awareness level: 25%"));
        assertTrue(projected.stream().anyMatch(message -> message instanceof AiMessage));
        assertTrue(projected.stream().anyMatch(message -> message instanceof ToolExecutionResultMessage));
    }

    @Test
    void compactionReplacesOldRawToolMessagesWithDeterministicJournalAndKeepsRecentInteraction() {
        ToolAwareTokenWindowChatMemory memory = memory(45, 12, 0.50, 1);
        memory.add(SystemMessage.from("system"));
        memory.add(UserMessage.from("tool pass"));
        addExecution(memory, "1", "search", "{\"q\":\"seed\"}", "[]", false);
        addExecution(memory, "2", "create_repo", "{\"readme\":\"long\"}", "[Readme]: MaxSize", true);

        List<ChatMessage> projected = memory.messages();
        String runtime = runtimeText(projected);

        assertTrue(runtime.contains("DETERMINISTIC EXECUTION JOURNAL"));
        assertTrue(runtime.contains("search({\"q\":\"seed\"})"));
        assertTrue(runtime.contains("SUCCESS: []"));
        assertFalse(projected.contains(AiMessage.from(request("1", "search", "{\"q\":\"seed\"}"))));
        assertTrue(projected.contains(AiMessage.from(request("2", "create_repo", "{\"readme\":\"long\"}"))));
    }

    @Test
    void repeatedIdenticalExecutionsRequireReassessment() {
        ToolAwareTokenWindowChatMemory memory = memory(1000, 8, 0.90, 3);
        memory.add(SystemMessage.from("system"));
        memory.add(UserMessage.from("tool pass"));
        addExecution(memory, "1", "create_repo", "{\"readme\":\"x\"}", "[Readme]: MaxSize", true);
        addExecution(memory, "2", "create_repo", "{\"readme\":\"x\"}", "[Readme]: MaxSize", true);

        String runtime = runtimeText(memory.messages());

        assertTrue(runtime.contains("deterministic repetition signal: 1"));
        assertTrue(runtime.contains("REASSESSMENT REQUIRED"));
        assertTrue(runtime.contains("choose materially different arguments"));
        assertTrue(runtime.contains("do not repeat an unchanged tool execution"));
    }

    @Test
    void runtimeWarningSinkGetsBriefBudgetAndRepetitionSummary() {
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
        addExecution(memory, "1", "create_repo", "{\"readme\":\"x\"}", "[Readme]: MaxSize", true);
        addExecution(memory, "2", "create_repo", "{\"readme\":\"x\"}", "[Readme]: MaxSize", true);

        memory.messages();

        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("tool-pass warning sent: budget=25%"));
        assertTrue(warnings.getFirst().contains("tools=2/8"));
        assertTrue(warnings.getFirst().contains("repeats=1 reassessment=required"));
        assertFalse(warnings.getFirst().contains("[Readme]: MaxSize"));
    }

    @Test
    void everyToolRequestConsumesToolBudget() {
        ToolAwareTokenWindowChatMemory memory = memory(1000, 4, 0.90, 3);
        memory.add(SystemMessage.from("system"));
        memory.add(UserMessage.from("tool pass"));
        memory.add(AiMessage.from(List.of(
                request("1", "search", "{}"),
                request("2", "lookup", "{}"))));

        assertTrue(runtimeText(memory.messages()).contains("tool calls: 2/4 (50%)"));
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

    private static String runtimeText(List<ChatMessage> messages) {
        return messages.stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .filter(message -> "tack-runtime".equals(message.name()))
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
