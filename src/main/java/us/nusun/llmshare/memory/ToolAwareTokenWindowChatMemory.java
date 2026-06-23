package us.nusun.llmshare.memory;

import static dev.langchain4j.internal.Utils.getOrDefault;
import static dev.langchain4j.internal.ValidationUtils.ensureGreaterThanZero;
import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.service.memory.ChatMemoryService;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Token-window chat memory scoped to a single agent invocation.
 * Eviction favors old tool-call history before ordinary conversational context.
 */
public final class ToolAwareTokenWindowChatMemory implements ChatMemory {
    private final Object id;
    private final Function<Object, Integer> maxTokensProvider;
    private final TokenCountEstimator tokenCountEstimator;
    private final boolean alwaysKeepSystemMessageFirst;

    private List<ChatMessage> messages = new ArrayList<>();

    private ToolAwareTokenWindowChatMemory(Builder builder) {
        this.id = ensureNotNull(builder.id, "id");
        this.maxTokensProvider = ensureNotNull(builder.maxTokensProvider, "maxTokensProvider");
        ensureGreaterThanZero(this.maxTokensProvider.apply(id), "maxTokens");
        this.tokenCountEstimator = ensureNotNull(builder.tokenCountEstimator, "tokenCountEstimator");
        this.alwaysKeepSystemMessageFirst = getOrDefault(builder.alwaysKeepSystemMessageFirst, false);
    }

    @Override
    public Object id() {
        return id;
    }

    @Override
    public synchronized void add(ChatMessage message) {
        List<ChatMessage> updatedMessages = messages();

        if (message instanceof SystemMessage) {
            Optional<SystemMessage> maybeSystemMessage = SystemMessage.findFirst(updatedMessages);
            if (maybeSystemMessage.isPresent()) {
                if (maybeSystemMessage.get().equals(message)) {
                    return;
                }
                updatedMessages.remove(maybeSystemMessage.get());
            }
        }

        if (message instanceof SystemMessage && alwaysKeepSystemMessageFirst) {
            updatedMessages.add(0, message);
        } else {
            updatedMessages.add(message);
        }

        ensureCapacity(updatedMessages, maxTokens(), tokenCountEstimator);
        this.messages = updatedMessages;
    }

    @Override
    public synchronized void set(Iterable<ChatMessage> iter) {
        List<ChatMessage> updatedMessages = new ArrayList<>();
        iter.forEach(updatedMessages::add);
        ensureCapacity(updatedMessages, maxTokens(), tokenCountEstimator);
        this.messages = updatedMessages;
    }

    @Override
    public synchronized List<ChatMessage> messages() {
        List<ChatMessage> snapshot = new LinkedList<>(messages);
        ensureCapacity(snapshot, maxTokens(), tokenCountEstimator);
        this.messages = snapshot;
        return new LinkedList<>(snapshot);
    }

    @Override
    public synchronized void clear() {
        this.messages = new ArrayList<>();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Object id = ChatMemoryService.DEFAULT;
        private Function<Object, Integer> maxTokensProvider;
        private TokenCountEstimator tokenCountEstimator;
        private Boolean alwaysKeepSystemMessageFirst;

        public Builder id(Object id) {
            this.id = id;
            return this;
        }

        public Builder maxTokens(Integer maxTokens, TokenCountEstimator tokenCountEstimator) {
            this.maxTokensProvider = ignored -> maxTokens;
            this.tokenCountEstimator = tokenCountEstimator;
            return this;
        }

        public Builder dynamicMaxTokens(
                Function<Object, Integer> maxTokensProvider, TokenCountEstimator tokenCountEstimator) {
            this.maxTokensProvider = maxTokensProvider;
            this.tokenCountEstimator = tokenCountEstimator;
            return this;
        }

        public Builder alwaysKeepSystemMessageFirst(Boolean alwaysKeepSystemMessageFirst) {
            this.alwaysKeepSystemMessageFirst = alwaysKeepSystemMessageFirst;
            return this;
        }

        public ToolAwareTokenWindowChatMemory build() {
            return new ToolAwareTokenWindowChatMemory(this);
        }
    }

    private int maxTokens() {
        Integer maxTokens = maxTokensProvider.apply(id);
        ensureGreaterThanZero(maxTokens, "maxTokens");
        return maxTokens;
    }

    private static void ensureCapacity(List<ChatMessage> messages, int maxTokens, TokenCountEstimator estimator) {
        if (messages.isEmpty()) {
            return;
        }

        int currentTokenCount = estimator.estimateTokenCountInMessages(messages);
        while (currentTokenCount > maxTokens) {
            int messageToEvictIndex = findOldestToolExecutionRequestIndex(messages)
                    .orElseGet(() -> oldestEvictableNonSystemNonCurrentUserMessageIndex(messages)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Ephemeral tool-pass memory exceeded the safe token budget while only protected messages remain")));

            ChatMessage evictedMessage = messages.remove(messageToEvictIndex);
            currentTokenCount -= estimator.estimateTokenCountInMessage(evictedMessage);

            if (evictedMessage instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests()) {
                while (messages.size() > messageToEvictIndex
                        && messages.get(messageToEvictIndex) instanceof ToolExecutionResultMessage) {
                    ChatMessage orphanToolExecutionResultMessage = messages.remove(messageToEvictIndex);
                    currentTokenCount -= estimator.estimateTokenCountInMessage(orphanToolExecutionResultMessage);
                }
            }
        }
    }

    static Optional<Integer> findOldestToolExecutionRequestIndex(List<ChatMessage> messages) {
        Optional<Integer> currentUserIndex = originalUserMessageIndex(messages);
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (message instanceof SystemMessage) {
                continue;
            }
            if (currentUserIndex.isPresent() && currentUserIndex.get() == i) {
                continue;
            }
            if (message instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests()) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }

    private static Optional<Integer> oldestEvictableNonSystemNonCurrentUserMessageIndex(List<ChatMessage> messages) {
        Optional<Integer> currentUserIndex = originalUserMessageIndex(messages);
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (message instanceof SystemMessage) {
                continue;
            }
            if (currentUserIndex.isPresent() && currentUserIndex.get() == i) {
                continue;
            }
            return Optional.of(i);
        }
        return Optional.empty();
    }

    private static Optional<Integer> originalUserMessageIndex(List<ChatMessage> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof UserMessage) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }
}
