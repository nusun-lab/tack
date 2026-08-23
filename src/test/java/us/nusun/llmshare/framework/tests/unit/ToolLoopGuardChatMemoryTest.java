package us.nusun.llmshare.framework.tests.unit;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.memory.ToolLoopGuardChatMemory;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolLoopGuardChatMemoryTest {

    @Test
    void thirdIdenticalRequestIsRejectedBeforeExecution() {
        RecordingMemory delegate = new RecordingMemory();
        ToolLoopGuardChatMemory memory = new ToolLoopGuardChatMemory(delegate);

        addExecution(memory, "1", "plane.search_work_items", "{\"query\":\"matchbox\"}", "{\"issues\":[]}");
        addExecution(memory, "2", "plane.search_work_items", "{\"query\":\"matchbox\"}", "{\"issues\":[]}");

        ToolLoopGuardChatMemory.RepeatedToolExecutionException failure = assertThrows(
                ToolLoopGuardChatMemory.RepeatedToolExecutionException.class,
                () -> memory.add(AiMessage.from(request("3", "plane.search_work_items", "{\"query\":\"matchbox\"}"))));

        assertTrue(failure.getMessage().contains("third execution was refused"));
        assertEquals(4, delegate.messages().size());
    }

    @Test
    void changedRequestBreaksTheLoopAndMayExecute() {
        ToolLoopGuardChatMemory memory = new ToolLoopGuardChatMemory(new RecordingMemory());
        addExecution(memory, "1", "search", "{\"q\":\"matchbox\"}", "[]");
        addExecution(memory, "2", "search", "{\"q\":\"matchbox\"}", "[]");

        assertDoesNotThrow(() -> memory.add(AiMessage.from(request("3", "search", "{\"q\":\"race-night\"}"))));
    }

    @Test
    void sameRequestWithDifferentResultsDoesNotTripTheGuard() {
        ToolLoopGuardChatMemory memory = new ToolLoopGuardChatMemory(new RecordingMemory());
        addExecution(memory, "1", "status", "{}", "pending");
        addExecution(memory, "2", "status", "{}", "ready");

        assertDoesNotThrow(() -> memory.add(AiMessage.from(request("3", "status", "{}"))));
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

    private static final class RecordingMemory implements ChatMemory {
        private final List<ChatMessage> messages = new ArrayList<>();

        @Override
        public Object id() {
            return "test";
        }

        @Override
        public void add(ChatMessage message) {
            messages.add(message);
        }

        @Override
        public void set(Iterable<ChatMessage> messages) {
            this.messages.clear();
            messages.forEach(this.messages::add);
        }

        @Override
        public List<ChatMessage> messages() {
            return new LinkedList<>(messages);
        }

        @Override
        public void clear() {
            messages.clear();
        }
    }
}
