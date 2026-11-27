package us.nusun.tack.framework.inference.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Indexes the original messages for tool budgets and repeated execution checks. */
final class ToolInteractions {
    private final List<ToolExecutionRequest> pending = new ArrayList<>();
    private final List<Execution> completed = new ArrayList<>();
    private int requestCount;

    void record(ChatMessage message) {
        if (message instanceof AiMessage assistant) {
            pending.addAll(assistant.toolExecutionRequests());
            requestCount += assistant.toolExecutionRequests().size();
        } else if (message instanceof ToolExecutionResultMessage result) {
            for (int i = pending.size() - 1; i >= 0; i--) {
                if (Objects.equals(pending.get(i).id(), result.id())) {
                    completed.add(new Execution(Signature.from(pending.remove(i)), result));
                    return;
                }
            }
        }
    }

    int requestCount() {
        return requestCount;
    }

    List<Execution> completed() {
        return completed;
    }

    void clear() {
        pending.clear();
        completed.clear();
        requestCount = 0;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }

    record Signature(String toolName, String arguments) {
        static Signature from(ToolExecutionRequest request) {
            return new Signature(normalize(request.name()), normalize(request.arguments()));
        }
    }

    record Execution(Signature request, ToolExecutionResultMessage result) {
        boolean matches(Execution other) {
            if (!request.equals(other.request) || !Objects.equals(result.isError(), other.result.isError())) {
                return false;
            }
            if (result.contents().equals(other.result.contents())) {
                return true;
            }
            return result.hasSingleText() && other.result.hasSingleText()
                    && normalize(result.text()).equals(normalize(other.result.text()));
        }
    }
}
