package us.nusun.tack.framework.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;

import java.util.LinkedList;
import java.util.List;

/**
 * Hard stop for pathological tool loops and tool-pass budget exhaustion.
 */
public final class ToolLoopGuardChatMemory implements ChatMemory {
    private static final System.Logger LOG = System.getLogger(ToolLoopGuardChatMemory.class.getName());
    private static final int MAX_CYCLE_LENGTH = 5;

    private final ChatMemory delegate;
    private final int maxToolCalls;
    private final ToolInteractions interactions = new ToolInteractions();
    private int acceptedToolCalls;
    private int assistantRounds;

    public ToolLoopGuardChatMemory(ChatMemory delegate) {
        this(delegate, 0);
    }

    public ToolLoopGuardChatMemory(ChatMemory delegate, int maxToolCalls) {
        this.delegate = delegate;
        this.maxToolCalls = Math.max(0, maxToolCalls);
    }

    @Override
    public Object id() {
        return delegate.id();
    }

    @Override
    public synchronized void add(ChatMessage message) {
        if (message instanceof AiMessage aiMessage) {
            assistantRounds++;
            List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
            LOG.log(System.Logger.Level.INFO,
                    "LLM tool-loop assistant round={0} thinkingChars={1} textChars={2} toolCalls={3} acceptedToolCalls={4}/{5}",
                    assistantRounds,
                    textLength(aiMessage.thinking()),
                    textLength(aiMessage.text()),
                    requests.size(),
                    acceptedToolCalls,
                    maxToolCalls);

            if (!aiMessage.hasToolExecutionRequests()) {
                delegate.add(message);
                return;
            }
            if (maxToolCalls > 0 && acceptedToolCalls + requests.size() > maxToolCalls) {
                throw new ToolCallBudgetExceededException(maxToolCalls, acceptedToolCalls, requests.size());
            }
            for (ToolExecutionRequest request : requests) {
                ToolInteractions.Signature signature = ToolInteractions.Signature.from(request);
                int cycleLength = repeatedCycleLength(signature);
                if (cycleLength > 0) {
                    throw new RepeatedToolExecutionException(signature.toolName(), cycleLength);
                }
            }
            delegate.add(message);
            acceptedToolCalls += requests.size();
            interactions.record(message);
            return;
        }

        delegate.add(message);
        interactions.record(message);
    }

    @Override
    public synchronized void set(Iterable<ChatMessage> messages) {
        clear();
        messages.forEach(this::add);
    }

    @Override
    public synchronized List<ChatMessage> messages() {
        return new LinkedList<>(delegate.messages());
    }

    @Override
    public synchronized void clear() {
        delegate.clear();
        interactions.clear();
        acceptedToolCalls = 0;
        assistantRounds = 0;
    }

    private int repeatedCycleLength(ToolInteractions.Signature nextRequest) {
        List<ToolInteractions.Execution> completedExecutions = interactions.completed();
        int completed = completedExecutions.size();
        for (int cycleLength = 1; cycleLength <= MAX_CYCLE_LENGTH; cycleLength++) {
            if (completed < cycleLength * 2) {
                continue;
            }
            int firstCycleStart = completed - cycleLength * 2;
            int secondCycleStart = completed - cycleLength;
            boolean sameCycle = true;
            for (int i = 0; i < cycleLength; i++) {
                if (!completedExecutions.get(firstCycleStart + i)
                        .equals(completedExecutions.get(secondCycleStart + i))) {
                    sameCycle = false;
                    break;
                }
            }
            if (sameCycle && completedExecutions.get(secondCycleStart).request().equals(nextRequest)) {
                return cycleLength;
            }
        }
        return 0;
    }

    private static int textLength(String value) {
        return value == null ? 0 : value.length();
    }

    public static class ToolPassTerminatedException extends RuntimeException {
        protected ToolPassTerminatedException(String message) {
            super(message);
        }
    }

    public static final class RepeatedToolExecutionException extends ToolPassTerminatedException {
        public RepeatedToolExecutionException(String toolName, int cycleLength) {
            super(cycleLength == 1
                    ? "TACK_RUNTIME_REPEATED_EXECUTION: Tool '" + toolName
                            + "' was requested unchanged after two identical completed executions. "
                            + "The third execution was refused and this tool pass was terminated. "
                            + "Use the existing tool results and choose a materially different approach in the next step."
                    : "TACK_RUNTIME_REPEATED_CYCLE: Tool '" + toolName
                            + "' would continue a repeated " + cycleLength + "-step tool cycle with unchanged results. "
                            + "The next execution was refused and this tool pass was terminated. "
                            + "Use the existing tool results and choose a materially different approach in the next step.");
        }
    }

    public static final class ToolCallBudgetExceededException extends ToolPassTerminatedException {
        public ToolCallBudgetExceededException(int maxToolCalls, int acceptedToolCalls, int requestedToolCalls) {
            super("TACK_RUNTIME_TOOL_BUDGET_EXHAUSTED: Tool-pass budget is " + maxToolCalls
                    + " individual tool calls. " + acceptedToolCalls + " have already been accepted and the model requested "
                    + requestedToolCalls + " more. The request was refused before execution and this tool pass was terminated.");
        }
    }
}
