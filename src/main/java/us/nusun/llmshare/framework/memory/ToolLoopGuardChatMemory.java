package us.nusun.llmshare.framework.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.ChatMemory;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Hard stop for unchanged tool loops. Two identical completed executions are allowed so the model can observe
 * repetition feedback; a third identical request is rejected before the underlying tool executes.
 */
public final class ToolLoopGuardChatMemory implements ChatMemory {
    private final ChatMemory delegate;
    private final Map<String, RequestSignature> pending = new HashMap<>();
    private ExecutionSignature latestExecution;
    private int consecutiveIdenticalExecutions;

    public ToolLoopGuardChatMemory(ChatMemory delegate) {
        this.delegate = delegate;
    }

    @Override
    public Object id() {
        return delegate.id();
    }

    @Override
    public synchronized void add(ChatMessage message) {
        if (message instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests()) {
            for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                RequestSignature signature = RequestSignature.from(request);
                if (consecutiveIdenticalExecutions >= 2
                        && latestExecution != null
                        && latestExecution.request().equals(signature)) {
                    throw new RepeatedToolExecutionException(signature.toolName());
                }
            }
            delegate.add(message);
            for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
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
            ExecutionSignature execution = new ExecutionSignature(request, normalize(resultText(result)), result.isError());
            if (execution.equals(latestExecution)) {
                consecutiveIdenticalExecutions++;
            } else {
                latestExecution = execution;
                consecutiveIdenticalExecutions = 1;
            }
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
        latestExecution = null;
        consecutiveIdenticalExecutions = 0;
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

    public static final class RepeatedToolExecutionException extends RuntimeException {
        public RepeatedToolExecutionException(String toolName) {
            super("TACK_RUNTIME_REPEATED_EXECUTION: Tool '" + toolName
                    + "' was requested unchanged after two identical completed executions. "
                    + "The third execution was refused and this tool pass was terminated. "
                    + "Use the existing tool results and choose a materially different approach in the next step.");
        }
    }
}
