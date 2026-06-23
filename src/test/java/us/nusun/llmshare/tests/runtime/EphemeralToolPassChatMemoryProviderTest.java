package us.nusun.llmshare.tests.runtime;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.memory.EphemeralToolPassChatMemoryProvider;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EphemeralToolPassChatMemoryProviderTest {

    @Test
    void newInvocationStartsWithFreshMemory() {
        EphemeralToolPassChatMemoryProvider provider =
                new EphemeralToolPassChatMemoryProvider(100, new ConstantTokenCountEstimator(), true);

        ChatMemory firstInvocation = provider.get("job-1");
        firstInvocation.add(new UserMessage("first"));

        ChatMemory secondInvocation = provider.get("job-2");

        assertNotSame(firstInvocation, secondInvocation);
        assertTrue(secondInvocation.messages().isEmpty());
    }

    private static final class ConstantTokenCountEstimator implements TokenCountEstimator {

        @Override
        public int estimateTokenCountInText(String text) {
            return 1;
        }

        @Override
        public int estimateTokenCountInMessage(dev.langchain4j.data.message.ChatMessage message) {
            return 1;
        }

        @Override
        public int estimateTokenCountInMessages(Iterable<dev.langchain4j.data.message.ChatMessage> messages) {
            int total = 0;
            for (dev.langchain4j.data.message.ChatMessage ignored : messages) {
                total++;
            }
            return total;
        }
    }
}
