package us.nusun.tack.framework.memory;

import static dev.langchain4j.internal.Utils.getOrDefault;
import static dev.langchain4j.internal.ValidationUtils.ensureGreaterThanZero;
import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
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
import java.util.function.Consumer;
import java.util.function.Function;

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
    private final Function<Object, Integer> maxTokensProvider;
    private final TokenCountEstimator tokenCountEstimator;
    private final boolean alwaysKeepSystemMessageFirst;
    private final int maxToolCalls;
    private final double compactionThreshold;
    private final int recentToolInteractions;
    private final Consumer<String> runtimeWarningSink;

    private List<ChatMessage> messages = new ArrayList<>();
    private final List<JournalEntry> journal = new ArrayList<>();
    private int toolCalls;
    private boolean compacted;
    private boolean degradationNoticeSent;

    private ToolAwareTokenWindowChatMemory(Builder builder) {
        this.id = ensureNotNull(builder.id, "id");
        this.maxTokensProvider = ensureNotNull(builder.maxTokensProvider, "maxTokensProvider");
        ensureGreaterThanZero(this.maxTokensProvider.apply(id), "maxTokens");
        this.tokenCountEstimator = ensureNotNull(builder.tokenCountEstimator, "tokenCountEstimator");
        this.alwaysKeepSystemMessageFirst = getOrDefault(builder.alwaysKeepSystemMessageFirst, false);
        this.maxToolCalls = Math.max(0, builder.maxToolCalls);
        this.compactionThreshold = validThreshold(builder.compactionThreshold);
        this.recentToolInteractions = Math.max(0, builder.recentToolInteractions);
        this.runtimeWarningSink = builder.runtimeWarningSink == null ? ignored -> { } : builder.runtimeWarningSink;
    }

    @Override
    public Object id() {
        return id;
    }

    @Override
    public synchronized void add(ChatMessage message) {
        List<ChatMessage> updatedMessages = new ArrayList<>(messages);
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
        record(message);
        this.messages = updatedMessages;
    }

    @Override
    public synchronized void set(Iterable<ChatMessage> iter) {
        this.messages = new ArrayList<>();
        this.journal.clear();
        this.toolCalls = 0;
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
        this.journal.clear();
        this.toolCalls = 0;
        this.compacted = false;
        this.degradationNoticeSent = false;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Object id = ChatMemoryService.DEFAULT;
        private Function<Object, Integer> maxTokensProvider;
        private TokenCountEstimator tokenCountEstimator;
        private Boolean alwaysKeepSystemMessageFirst;
        private int maxToolCalls;
        private double compactionThreshold = 0.50;
        private int recentToolInteractions = 3;
        private Consumer<String> runtimeWarningSink;

        public Builder id(Object id) {
            this.id = id;
            return this;
        }

        public Builder maxTokens(Integer maxTokens, TokenCountEstimator tokenCountEstimator) {
            this.maxTokensProvider = ignored -> maxTokens;
            this.tokenCountEstimator = tokenCountEstimator;
            return this;
        }

        public Builder dynamicMaxTokens(Function<Object, Integer> maxTokensProvider, TokenCountEstimator tokenCountEstimator) {
            this.maxTokensProvider = maxTokensProvider;
            this.tokenCountEstimator = tokenCountEstimator;
            return this;
        }

        public Builder alwaysKeepSystemMessageFirst(Boolean alwaysKeepSystemMessageFirst) {
            this.alwaysKeepSystemMessageFirst = alwaysKeepSystemMessageFirst;
            return this;
        }

        public Builder maxToolCalls(int maxToolCalls) {
            this.maxToolCalls = maxToolCalls;
            return this;
        }

        public Builder compactionThreshold(double compactionThreshold) {
            this.compactionThreshold = compactionThreshold;
            return this;
        }

        public Builder recentToolInteractions(int recentToolInteractions) {
            this.recentToolInteractions = recentToolInteractions;
            return this;
        }

        public Builder runtimeWarningSink(Consumer<String> runtimeWarningSink) {
            this.runtimeWarningSink = runtimeWarningSink;
            return this;
        }

        public ToolAwareTokenWindowChatMemory build() {
            return new ToolAwareTokenWindowChatMemory(this);
        }
    }

    private List<ChatMessage> project(List<ChatMessage> rawMessages) {
        if (rawMessages.isEmpty()) {
            return List.of();
        }
        int rawTokens = tokenCountEstimator.estimateTokenCountInMessages(rawMessages);
        double historyPressure = ratio(rawTokens, maxTokens());

        if (!compacted && historyPressure < compactionThreshold) {
            List<ChatMessage> projected = new ArrayList<>(rawMessages);
            notifyRuntimeBudget(rawTokens, rawTokens, false);
            return projected;
        }

        compacted = true;
        int rawStart = recentToolHistoryStart(rawMessages, recentToolInteractions);
        int journalCutoff = Math.max(0, journal.size() - recentToolInteractions);
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
        if (projectedTokens > maxTokens()) {
            projected = protectedPrefix(rawMessages);
            addDegradationMessageIfNeeded(projected);
            addJournal(projected, journal.size());
            projectedTokens = tokenCountEstimator.estimateTokenCountInMessages(projected);
        }
        if (projectedTokens > maxTokens()) {
            throw new IllegalStateException(
                    "Ephemeral tool-pass projection exceeded the safe token budget while only protected messages and deterministic journal remain");
        }
        notifyRuntimeBudget(rawTokens, projectedTokens, true);
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

    private void notifyRuntimeBudget(int rawTokens, int projectedTokens, boolean compacted) {
        StringBuilder summary = new StringBuilder("TOKEN BUDGET transcript=")
                .append(projectedTokens).append('/').append(maxTokens())
                .append(" (").append(percent(ratio(projectedTokens, maxTokens()))).append("%)")
                .append(" rawTranscript=").append(rawTokens)
                .append(" compacted=").append(compacted)
                .append(" calls=").append(toolCalls);
        if (maxToolCalls > 0) {
            summary.append('/').append(maxToolCalls);
        }
        int repeated = repeatedExecutions();
        if (repeated > 0) {
            summary.append(" repeats=").append(repeated);
        }
        runtimeWarningSink.accept(summary.toString());
    }

    private void appendJournal(StringBuilder text, int entries) {
        int limit = Math.min(entries, journal.size());
        int i = 0;
        while (i < limit) {
            JournalEntry entry = journal.get(i);
            int repeats = 1;
            while (i + repeats < limit && entry.sameExecution(journal.get(i + repeats))) {
                repeats++;
            }
            text.append(i + 1).append(". ").append(entry.toolName).append('(')
                    .append(preview(entry.arguments)).append(")\n   -> ")
                    .append(entry.resultLabel()).append(": ").append(preview(entry.resultText));
            if (repeats > 1) {
                text.append("\n   -> identical execution repeated ").append(repeats).append(" times");
            }
            text.append('\n');
            i += repeats;
        }
    }

    private int repeatedExecutions() {
        if (journal.size() < 2) {
            return 0;
        }
        JournalEntry latest = journal.getLast();
        if (latest.resultText == null) {
            return 0;
        }
        int repeated = 0;
        for (int i = journal.size() - 2; i >= 0; i--) {
            if (!latest.sameExecution(journal.get(i))) {
                break;
            }
            repeated++;
        }
        return repeated;
    }

    private void record(ChatMessage message) {
        if (message instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests()) {
            for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                toolCalls++;
                journal.add(new JournalEntry(request.id(), request.name(), normalize(request.arguments())));
            }
            return;
        }
        if (message instanceof ToolExecutionResultMessage result) {
            for (int i = journal.size() - 1; i >= 0; i--) {
                JournalEntry entry = journal.get(i);
                if (entry.resultText == null && same(entry.requestId, result.id())) {
                    entry.resultText = safeText(result);
                    entry.isError = result.isError();
                    return;
                }
            }
        }
    }

    private static String safeText(ToolExecutionResultMessage result) {
        try {
            return normalize(result.text());
        } catch (Exception e) {
            return normalize(String.valueOf(result.contents()));
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

    private static Optional<Integer> originalUserMessageIndex(List<ChatMessage> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof UserMessage) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }

    private int maxTokens() {
        Integer maxTokens = maxTokensProvider.apply(id);
        ensureGreaterThanZero(maxTokens, "maxTokens");
        return maxTokens;
    }

    private static double ratio(int used, int max) {
        return max <= 0 ? 0.0 : (double) used / (double) max;
    }

    private static int percent(double ratio) {
        return (int) Math.round(ratio * 100.0);
    }

    private static double validThreshold(double value) {
        if (value <= 0.0 || value > 1.0) {
            throw new IllegalArgumentException("compactionThreshold must be > 0 and <= 1");
        }
        return value;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }

    private static String preview(String value) {
        String normalized = normalize(value);
        return normalized.length() <= JOURNAL_VALUE_PREVIEW_CHARS
                ? normalized
                : normalized.substring(0, JOURNAL_VALUE_PREVIEW_CHARS) + "...(truncated)";
    }

    private static boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static final class JournalEntry {
        private final String requestId;
        private final String toolName;
        private final String arguments;
        private String resultText;
        private Boolean isError;

        private JournalEntry(String requestId, String toolName, String arguments) {
            this.requestId = requestId;
            this.toolName = toolName == null ? "unknown" : toolName;
            this.arguments = arguments == null ? "" : arguments;
        }

        private String resultLabel() {
            if (Boolean.TRUE.equals(isError)) return "ERROR";
            if (Boolean.FALSE.equals(isError)) return "SUCCESS";
            return "RESULT";
        }

        private boolean sameExecution(JournalEntry other) {
            return other != null
                    && toolName.equals(other.toolName)
                    && arguments.equals(other.arguments)
                    && same(resultText, other.resultText)
                    && sameBoolean(isError, other.isError);
        }

        private static boolean sameBoolean(Boolean left, Boolean right) {
            return left == null ? right == null : left.equals(right);
        }
    }
}
