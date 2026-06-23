package us.nusun.llmshare.tests.runtime;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.memory.ToolAwareTokenWindowChatMemory;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolAwareTokenWindowChatMemoryTest {

    private final TokenCountEstimator estimator = new TestTokenCountEstimator();

    @Test
    void accumulatesMessagesDuringSingleInvocation() {
        ToolAwareTokenWindowChatMemory memory = ToolAwareTokenWindowChatMemory.builder()
                .id("job-1")
                .maxTokens(200, estimator)
                .alwaysKeepSystemMessageFirst(true)
                .build();

        memory.add(new SystemMessage("system"));
        memory.add(new UserMessage("user"));
        memory.add(toolRequest("tool-1"));
        memory.add(toolResult("tool-1", "result-1"));
        memory.add(new AiMessage("done"));

        List<ChatMessage> messages = memory.messages();
        assertEquals(5, messages.size());
        assertInstanceOf(SystemMessage.class, messages.get(0));
        assertInstanceOf(UserMessage.class, messages.get(1));
        assertInstanceOf(AiMessage.class, messages.get(2));
        assertInstanceOf(ToolExecutionResultMessage.class, messages.get(3));
        assertInstanceOf(AiMessage.class, messages.get(4));
    }

    @Test
    void evictsOldestToolExecutionGroupBeforeOrdinaryConversationMessages() {
        ToolAwareTokenWindowChatMemory memory = ToolAwareTokenWindowChatMemory.builder()
                .id("job-2")
                .maxTokens(42, estimator)
                .alwaysKeepSystemMessageFirst(true)
                .build();

        memory.set(List.of(
                new SystemMessage("system"),
                new UserMessage("user"),
                new AiMessage("keep plain context"),
                toolRequest("tool-1"),
                toolResult("tool-1", "result-1"),
                new AiMessage("latest continuation")
        ));

        List<ChatMessage> messages = memory.messages();
        assertEquals(4, messages.size());
        assertTrue(messages.stream().noneMatch(message -> message instanceof ToolExecutionResultMessage));
        assertEquals("keep plain context", ((AiMessage) messages.get(2)).text());
        assertEquals("latest continuation", ((AiMessage) messages.get(3)).text());
    }

    @Test
    void evictingToolRequestAlsoRemovesFollowingToolResults() {
        ToolAwareTokenWindowChatMemory memory = ToolAwareTokenWindowChatMemory.builder()
                .id("job-3")
                .maxTokens(35, estimator)
                .alwaysKeepSystemMessageFirst(true)
                .build();

        memory.set(List.of(
                new SystemMessage("system"),
                new UserMessage("user"),
                toolRequest("tool-1"),
                toolResult("tool-1", "result-1"),
                toolResult("tool-1", "result-2"),
                new AiMessage("tail")
        ));

        List<ChatMessage> messages = memory.messages();
        assertEquals(3, messages.size());
        assertInstanceOf(SystemMessage.class, messages.get(0));
        assertInstanceOf(UserMessage.class, messages.get(1));
        assertEquals("tail", ((AiMessage) messages.get(2)).text());
    }

    @Test
    void leadingSystemMessageIsPreserved() {
        ToolAwareTokenWindowChatMemory memory = ToolAwareTokenWindowChatMemory.builder()
                .id("job-4")
                .maxTokens(30, estimator)
                .alwaysKeepSystemMessageFirst(true)
                .build();

        memory.set(List.of(
                new SystemMessage("system"),
                new UserMessage("user"),
                new AiMessage("older"),
                new AiMessage("newer")
        ));

        assertInstanceOf(SystemMessage.class, memory.messages().get(0));
    }

    @Test
    void currentUserMessageIsNotSilentlyEvicted() {
        ToolAwareTokenWindowChatMemory memory = ToolAwareTokenWindowChatMemory.builder()
                .id("job-5")
                .maxTokens(15, estimator)
                .alwaysKeepSystemMessageFirst(true)
                .build();

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> memory.set(List.of(
                new SystemMessage("system"),
                new UserMessage("user")
        )));

        assertTrue(exception.getMessage().contains("safe token budget"));
    }

    @Test
    void fallbackTrimmingWorksWhenThereAreNoToolGroups() {
        ToolAwareTokenWindowChatMemory memory = ToolAwareTokenWindowChatMemory.builder()
                .id("job-6")
                .maxTokens(30, estimator)
                .alwaysKeepSystemMessageFirst(true)
                .build();

        memory.set(List.of(
                new SystemMessage("system"),
                new UserMessage("user"),
                new AiMessage("older"),
                new AiMessage("newer")
        ));

        List<ChatMessage> messages = memory.messages();
        assertEquals(3, messages.size());
        assertEquals("newer", ((AiMessage) messages.get(2)).text());
    }

    @Test
    void addSetAndMessagesAllEnforceCapacity() {
        AtomicInteger maxTokens = new AtomicInteger(30);
        ToolAwareTokenWindowChatMemory memory = ToolAwareTokenWindowChatMemory.builder()
                .id("job-7")
                .dynamicMaxTokens(ignored -> maxTokens.get(), estimator)
                .alwaysKeepSystemMessageFirst(true)
                .build();

        memory.add(new SystemMessage("system"));
        memory.add(new UserMessage("user"));
        memory.add(new AiMessage("older"));
        memory.add(new AiMessage("newer"));
        assertEquals(3, memory.messages().size());

        memory.set(List.of(
                new SystemMessage("system"),
                new UserMessage("user"),
                toolRequest("tool-1"),
                toolResult("tool-1", "result-1"),
                new AiMessage("tail")
        ));
        assertEquals(3, memory.messages().size());

        maxTokens.set(15);
        assertThrows(IllegalStateException.class, memory::messages);
    }

    private static AiMessage toolRequest(String toolId) {
        return new AiMessage(List.of(ToolExecutionRequest.builder()
                .id(toolId)
                .name("tool")
                .arguments("{\"id\":\"" + toolId + "\"}")
                .build()));
    }

    private static ToolExecutionResultMessage toolResult(String toolId, String text) {
        return new ToolExecutionResultMessage(toolId, "tool", text);
    }

    private static final class TestTokenCountEstimator implements TokenCountEstimator {

        @Override
        public int estimateTokenCountInText(String text) {
            return text == null || text.isBlank() ? 0 : 1;
        }

        @Override
        public int estimateTokenCountInMessage(ChatMessage message) {
            if (message instanceof SystemMessage) {
                return 8;
            }
            if (message instanceof UserMessage) {
                return 8;
            }
            if (message instanceof ToolExecutionResultMessage) {
                return 8;
            }
            if (message instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests()) {
                return 10;
            }
            if (message instanceof AiMessage) {
                return 10;
            }
            return 1;
        }

        @Override
        public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
            int total = 0;
            for (ChatMessage message : messages) {
                total += estimateTokenCountInMessage(message);
            }
            return total;
        }
    }
}
