package us.nusun.tack.framework.inference.memory;

import static dev.langchain4j.internal.ValidationUtils.ensureGreaterThanZero;
import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.TokenCountEstimator;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

/**
 * Ephemeral tool-pass memory that keeps the complete tool execution history internally while projecting a
 * smaller deterministic view to the model as context pressure rises.
 */
public final class ToolAwareTokenWindowChatMemory implements ChatMemory {
    private static final String JOURNAL_MESSAGE_NAME = "execution-journal";
    private static final String DEGRADATION_MESSAGE_NAME = "tack-runtime";
    private static final String DEGRADATION_MESSAGE =
            "Tool transcript history limit reached. Older messages are being replaced with lower-fidelity summaries. "
                    + "Return a final answer as soon as possible.";
    private static final int JOURNAL_VALUE_PREVIEW_CHARS = 320;

    private final Object id;
    private final int maxTokens;
    private final TokenCountEstimator tokenCountEstimator;
    private final double compactionThreshold;
    private final int recentToolInteractions;

    private List<ChatMessage> messages = new ArrayList<>();
    private final ToolInteractions interactions = new ToolInteractions();
    private boolean compacted;
    private boolean degradationNoticeSent;

    ToolAwareTokenWindowChatMemory(Object id, int maxTokens, TokenCountEstimator tokenCountEstimator,
                                   double compactionThreshold, int recentToolInteractions) {
        this.id = ensureNotNull(id, "id");
        this.maxTokens = ensureGreaterThanZero(maxTokens, "maxTokens");
        this.tokenCountEstimator = ensureNotNull(tokenCountEstimator, "tokenCountEstimator");
        this.compactionThreshold = validThreshold(compactionThreshold);
        this.recentToolInteractions = Math.max(0, recentToolInteractions);
    }

    @Override
    public Object id() {
        return id;
    }

    @Override
    public synchronized void add(ChatMessage message) {
        if (message instanceof SystemMessage) {
            if (!messages.isEmpty() && message.equals(messages.getFirst())) {
                return;
            }
            if (!messages.isEmpty() && messages.getFirst() instanceof SystemMessage) {
                messages.removeFirst();
            }
            messages.addFirst(message);
        } else {
            messages.add(message);
        }
        interactions.record(message);
    }

    @Override
    public synchronized void set(Iterable<ChatMessage> iter) {
        this.messages = new ArrayList<>();
        this.interactions.clear();
        this.compacted = false;
        this.degradationNoticeSent = false;
        iter.forEach(this::add);
    }

    @Override
    public synchronized List<ChatMessage> messages() {
        return new LinkedList<>(project(messages));
    }

    @Override
    public synchronized void clear() {
        this.messages = new ArrayList<>();
        this.interactions.clear();
        this.compacted = false;
        this.degradationNoticeSent = false;
    }

    private List<ChatMessage> project(List<ChatMessage> rawMessages) {
        if (rawMessages.isEmpty()) {
            return List.of();
        }
        int rawTokens = tokenCountEstimator.estimateTokenCountInMessages(rawMessages);
        if (!compacted && (double) rawTokens / maxTokens < compactionThreshold) {
            return rawMessages;
        }

        compacted = true;
        int rawStart = recentToolHistoryStart(rawMessages, recentToolInteractions);
        int journalCutoff = Math.max(0, interactions.requested().size() - recentToolInteractions);
        List<ChatMessage> projected = protectedPrefix(rawMessages);
        addDegradationMessageIfNeeded(projected);
        addJournal(projected, journalCutoff);
        for (int i = rawStart; i < rawMessages.size(); i++) {
            ChatMessage message = rawMessages.get(i);
            if (!alreadyProtected(projected, message)) {
                projected.add(message);
            }
        }

        int projectedTokens = tokenCountEstimator.estimateTokenCountInMessages(projected);
        if (projectedTokens > maxTokens) {
            projected = protectedPrefix(rawMessages);
            addDegradationMessageIfNeeded(projected);
            addJournal(projected, interactions.requested().size());
            projectedTokens = tokenCountEstimator.estimateTokenCountInMessages(projected);
        }
        if (projectedTokens > maxTokens) {
            throw new IllegalStateException(
                    "Ephemeral tool-pass projection exceeded the safe token budget while only protected messages and deterministic journal remain");
        }
        return projected;
    }

    private void addDegradationMessageIfNeeded(List<ChatMessage> projected) {
        if (!degradationNoticeSent) {
            projected.add(UserMessage.from(DEGRADATION_MESSAGE_NAME, DEGRADATION_MESSAGE));
            degradationNoticeSent = true;
        }
    }

    private void addJournal(List<ChatMessage> projected, int entries) {
        if (entries <= 0) {
            return;
        }
        StringBuilder text = new StringBuilder("Execution journal\n");
        appendJournal(text, entries);
        projected.add(UserMessage.from(JOURNAL_MESSAGE_NAME, text.toString().strip()));
    }

    private void appendJournal(StringBuilder text, int entries) {
        int limit = Math.min(entries, interactions.requested().size());
        int i = 0;
        while (i < limit) {
            ToolInteractions.Interaction entry = interactions.requested().get(i);
            int repeats = 1;
            while (i + repeats < limit && entry.sameExecution(interactions.requested().get(i + repeats))) {
                repeats++;
            }
            text.append(i + 1).append(". ").append(entry.tool()).append('(')
                    .append(preview(entry.arguments())).append(")\n   -> ")
                    .append(entry.resultLabel()).append(": ").append(preview(entry.resultText()));
            if (repeats > 1) {
                text.append("\n   -> identical execution repeated ").append(repeats).append(" times");
            }
            text.append('\n');
            i += repeats;
        }
    }

    private static List<ChatMessage> protectedPrefix(List<ChatMessage> rawMessages) {
        List<ChatMessage> result = new ArrayList<>();
        SystemMessage.findFirst(rawMessages).ifPresent(result::add);
        originalUserMessage(rawMessages).ifPresent(message -> {
            if (!result.contains(message)) {
                result.add(message);
            }
        });
        return result;
    }

    private static boolean alreadyProtected(List<ChatMessage> projected, ChatMessage message) {
        return message instanceof SystemMessage
                || (message instanceof UserMessage && projected.contains(message));
    }

    private static int recentToolHistoryStart(List<ChatMessage> messages, int interactions) {
        if (interactions <= 0) {
            return messages.size();
        }
        int seen = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests()) {
                seen++;
                if (seen >= interactions) {
                    return i;
                }
            }
        }
        return 0;
    }

    private static Optional<UserMessage> originalUserMessage(List<ChatMessage> messages) {
        return messages.stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast).findFirst();
    }

    private static double validThreshold(double value) {
        if (value <= 0.0 || value > 1.0) {
            throw new IllegalArgumentException("compactionThreshold must be > 0 and <= 1");
        }
        return value;
    }

    private static String preview(String value) {
        String normalized = ToolInteractions.normalize(value);
        return normalized.length() <= JOURNAL_VALUE_PREVIEW_CHARS
                ? normalized
                : normalized.substring(0, JOURNAL_VALUE_PREVIEW_CHARS) + "...(truncated)";
    }
}
