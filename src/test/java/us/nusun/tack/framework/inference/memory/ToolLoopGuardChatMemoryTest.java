package us.nusun.tack.framework.inference.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.Test;
import us.nusun.tack.framework.inference.lane.ToolPassSettings;
import us.nusun.tack.framework.tests.support.TestTokenCountEstimator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolLoopGuardChatMemoryTest {

    @Test
    void thirdIdenticalRequestIsRejectedBeforeExecution() {
        ChatMemory memory = memory(0);

        addExecution(memory, "1", "catalog.search", "{\"query\":\"matchbox\"}", "{\"issues\":[]}");
        addExecution(memory, "2", "catalog.search", "{\"query\":\"matchbox\"}", "{\"issues\":[]}");

        ToolLoopGuardChatMemory.RepeatedToolExecutionException failure = assertThrows(
                ToolLoopGuardChatMemory.RepeatedToolExecutionException.class,
                () -> memory.add(AiMessage.from(request("3", "catalog.search", "{\"query\":\"matchbox\"}"))));

        assertTrue(failure.getMessage().contains("third execution was refused"));
        assertEquals(4, memory.messages().size());
    }

    @Test
    void alternatingTwoStepCycleIsRejectedBeforeFifthExecution() {
        ChatMemory memory = memory(0);
        String publicSearch = "{\"query\":\"matchbox-race-night-app\",\"isPrivate\":false}";
        String privateSearch = "{\"query\":\"matchbox-race-night-app\",\"isPrivate\":true}";

        addExecution(memory, "1", "catalog.discover", publicSearch, "[]");
        addExecution(memory, "2", "catalog.discover", privateSearch, "[]");
        addExecution(memory, "3", "catalog.discover", publicSearch, "[]");
        addExecution(memory, "4", "catalog.discover", privateSearch, "[]");

        ToolLoopGuardChatMemory.RepeatedToolExecutionException failure = assertThrows(
                ToolLoopGuardChatMemory.RepeatedToolExecutionException.class,
                () -> memory.add(AiMessage.from(request("5", "catalog.discover", publicSearch))));

        assertTrue(failure.getMessage().contains("TACK_RUNTIME_REPEATED_CYCLE"));
        assertTrue(failure.getMessage().contains("2-step tool cycle"));
        assertEquals(8, memory.messages().size());
    }

    @Test
    void repeatedFiveStepCycleIsRejectedBeforeEleventhExecution() {
        ChatMemory memory = memory(0);

        for (int repetition = 0; repetition < 2; repetition++) {
            for (int step = 0; step < 5; step++) {
                String id = String.valueOf(repetition * 5 + step + 1);
                addExecution(memory, id, "tool-" + step, "{\"step\":" + step + "}", "result-" + step);
            }
        }

        ToolLoopGuardChatMemory.RepeatedToolExecutionException failure = assertThrows(
                ToolLoopGuardChatMemory.RepeatedToolExecutionException.class,
                () -> memory.add(AiMessage.from(request("11", "tool-0", "{\"step\":0}"))));

        assertTrue(failure.getMessage().contains("TACK_RUNTIME_REPEATED_CYCLE"));
        assertTrue(failure.getMessage().contains("5-step tool cycle"));
        assertEquals(20, memory.messages().size());
    }

    @Test
    void changedRequestBreaksTheLoopAndMayExecute() {
        ChatMemory memory = memory(0);
        addExecution(memory, "1", "search", "{\"q\":\"matchbox\"}", "[]");
        addExecution(memory, "2", "search", "{\"q\":\"matchbox\"}", "[]");

        assertDoesNotThrow(() -> memory.add(AiMessage.from(request("3", "search", "{\"q\":\"race-night\"}"))));
    }

    @Test
    void sameRequestWithDifferentResultsDoesNotTripTheGuard() {
        ChatMemory memory = memory(0);
        addExecution(memory, "1", "status", "{}", "pending");
        addExecution(memory, "2", "status", "{}", "ready");

        assertDoesNotThrow(() -> memory.add(AiMessage.from(request("3", "status", "{}"))));
    }

    @Test
    void individualToolBudgetIsEnforcedBeforeExecution() {
        ChatMemory memory = memory(2);
        addExecution(memory, "1", "search", "{\"q\":\"one\"}", "[]");
        addExecution(memory, "2", "search", "{\"q\":\"two\"}", "[]");

        ToolLoopGuardChatMemory.ToolCallBudgetExceededException failure = assertThrows(
                ToolLoopGuardChatMemory.ToolCallBudgetExceededException.class,
                () -> memory.add(AiMessage.from(request("3", "search", "{\"q\":\"three\"}"))));

        assertTrue(failure.getMessage().contains("TACK_RUNTIME_TOOL_BUDGET_EXHAUSTED"));
        assertEquals(4, memory.messages().size());
    }

    @Test
    void toolPressureDoesNotModifyToolResults() {
        ChatMemory memory = memory(4);
        addExecution(memory, "1", "search", "{\"q\":\"one\"}", "[]");

        ToolExecutionResultMessage result = (ToolExecutionResultMessage) memory.messages().getLast();

        assertEquals("[]", result.text());
    }

    @Test
    void compactionKeepsTheCompleteHistoryAvailableToTheGuard() {
        ChatMemory memory = ToolLoopGuardChatMemory.forPass("test",
                new ToolPassSettings(1000, 5, 0.001, 1, 20000, 2000), new TestTokenCountEstimator());
        memory.add(UserMessage.from("Check the current status."));
        addExecution(memory, "1", "status", "{}", "pending");
        addExecution(memory, "2", "status", "{}", "pending");

        assertEquals(1, memory.messages().stream().filter(ToolExecutionResultMessage.class::isInstance).count());
        assertThrows(ToolLoopGuardChatMemory.RepeatedToolExecutionException.class,
                () -> memory.add(AiMessage.from(request("3", "status", "{}"))));
    }

    private static void addExecution(ChatMemory memory, String id, String name, String arguments, String result) {
        memory.add(AiMessage.from(request(id, name, arguments)));
        memory.add(ToolExecutionResultMessage.builder()
                .id(id)
                .toolName(name)
                .text(result)
                .isError(false)
                .build());
    }

    private static ToolExecutionRequest request(String id, String name, String arguments) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments(arguments).build();
    }

    private static ChatMemory memory(int maxCalls) {
        return ToolLoopGuardChatMemory.forPass("test", new ToolPassSettings(10000, maxCalls, 1.0, 3, 20000, 2000),
                new TestTokenCountEstimator());
    }
}
