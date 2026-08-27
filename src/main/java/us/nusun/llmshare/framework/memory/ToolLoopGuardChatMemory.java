package us.nusun.llmshare.framework.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.ChatMemory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Hard stop for pathological tool loops and tool-pass budget exhaustion.
 */
public final class ToolLoopGuardChatMemory implements ChatMemory {
    private static final int MAX_CYCLE_LENGTH = 5;

    private final ChatMemory delegate;
    private final int maxToolCalls;
    private final Map<String, RequestSignature> pending = new HashMap<>();
    private final List<ExecutionSignature> completedExecutions = new ArrayList<>();
    private int acceptedToolCalls;

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
        if (message instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests()) {
            List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
            if (maxToolCalls > 0 && acceptedToolCalls + requests.size() > maxToolCalls) {
                throw new ToolCallBudgetExceededException(maxToolCalls, acceptedToolCalls, requests.size());
            }
            for (ToolExecutionRequest request : requests) {
                RequestSignature signature = RequestSignature.from(request);
                int cycleLength = repeatedCycleLength(signature);
                if (cycleLength > 0) {
                    throw new RepeatedToolExecutionException(signature.toolName(), cycleLength);
                }
            }
            delegate.add(message);
            acceptedToolCalls += requests.size();
            for (ToolExecutionRequest request : requests) {
                pending.put(request.id(), RequestSignature.from(request));
            }
            return;
        }

        delegate.add(message);
        if (message instanceof ToolExecutionResultMessage result) {
            RequestSignature request = pending.remove(result.id());
            if (request == null) {
                return;
            }
            completedExecutions.add(new ExecutionSignature(
                    request,
                    normalize(resultText(result)),
                    result.isError()));
        }
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
        pending.clear();
        completedExecutions.clear();
        acceptedToolCalls = 0;
    }

    private int repeatedCycleLength(RequestSignature nextRequest) {
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

    private static String resultText(ToolExecutionResultMessage result) {
        try {
            return result.text();
        } catch (Exception ignored) {
            return String.valueOf(result.contents());
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }

    private record RequestSignature(String toolName, String arguments) {
        private static RequestSignature from(ToolExecutionRequest request) {
            return new RequestSignature(normalize(request.name()), normalize(request.arguments()));
        }
    }

    private record ExecutionSignature(RequestSignature request, String result, boolean error) {
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
