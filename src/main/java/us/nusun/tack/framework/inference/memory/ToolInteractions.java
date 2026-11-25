package us.nusun.tack.framework.inference.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Correlates tool requests with results while preserving request and completion order. */
final class ToolInteractions {
    private final List<Interaction> requested = new ArrayList<>();
    private final List<Execution> completed = new ArrayList<>();

    void record(ChatMessage message) {
        if (message instanceof AiMessage assistant) {
            for (ToolExecutionRequest request : assistant.toolExecutionRequests()) {
                requested.add(new Interaction(request.id(), request.name() == null ? "unknown" : request.name(),
                        normalize(request.arguments()), null));
            }
        } else if (message instanceof ToolExecutionResultMessage result) {
            for (int i = requested.size() - 1; i >= 0; i--) {
                Interaction pending = requested.get(i);
                if (pending.execution() == null && Objects.equals(pending.id(), result.id())) {
                    Execution execution = new Execution(new Signature(normalize(pending.tool()), pending.arguments()),
                            resultText(result), result.isError());
                    requested.set(i, new Interaction(pending.id(), pending.tool(), pending.arguments(), execution));
                    completed.add(execution);
                    return;
                }
            }
        }
    }

    List<Interaction> requested() {
        return requested;
    }

    List<Execution> completed() {
        return completed;
    }

    void clear() {
        requested.clear();
        completed.clear();
    }

    static String normalize(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }

    private static String resultText(ToolExecutionResultMessage result) {
        try {
            return normalize(result.text());
        } catch (IllegalArgumentException | IllegalStateException failure) {
            return normalize(String.valueOf(result.contents()));
        }
    }

    record Signature(String toolName, String arguments) {
        static Signature from(ToolExecutionRequest request) {
            return new Signature(normalize(request.name()), normalize(request.arguments()));
        }
    }

    record Execution(Signature request, String text, boolean error) {}

    record Interaction(String id, String tool, String arguments, Execution execution) {
        String resultLabel() {
            return execution == null ? "RESULT" : execution.error() ? "ERROR" : "SUCCESS";
        }

        String resultText() {
            return execution == null ? null : execution.text();
        }

        boolean sameExecution(Interaction other) {
            return tool.equals(other.tool()) && arguments.equals(other.arguments())
                    && Objects.equals(execution, other.execution());
        }
    }
}
