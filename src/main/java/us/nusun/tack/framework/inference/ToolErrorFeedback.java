package us.nusun.tack.framework.inference;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.service.tool.ToolErrorContext;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Converts tool failures into concise feedback that LangChain4j sends back to the model.
 */
public final class ToolErrorFeedback {
    private static final Logger LOG = LoggerFactory.getLogger(ToolErrorFeedback.class);
    private static final int MAX_REASON_LENGTH = 1500;

    private ToolErrorFeedback() {
    }

    public static ToolErrorHandlerResult execution(Throwable error, ToolErrorContext context) {
        return ToolErrorHandlerResult.text("Tool '" + toolName(context) + "' failed. Reason: " + reason(error));
    }

    public static ToolErrorHandlerResult arguments(Throwable error, ToolErrorContext context) {
        return ToolErrorHandlerResult.text("Tool '" + toolName(context) + "' received invalid arguments. Reason: " + reason(error));
    }

    static ToolExecutionResultMessage unavailableTool(ToolExecutionRequest request) {
        String toolName = request == null ? "" : request.name();
        LOG.warn("Recovering from hallucinated tool name: {}", toolName);
        return ToolExecutionResultMessage.builder()
                .id(request == null ? null : request.id())
                .toolName(toolName)
                .text("""
                        No tool named '%s' is available in the current tool profile.
                        Do not retry this exact tool name during this pass.
                        Use only the currently available tools. Choose another approach or continue without this operation.
                        """.formatted(toolName).strip())
                .isError(true)
                .build();
    }

    private static String toolName(ToolErrorContext context) {
        if (context == null || context.toolExecutionRequest() == null || context.toolExecutionRequest().name() == null
                || context.toolExecutionRequest().name().isBlank()) {
            return "unknown";
        }
        return context.toolExecutionRequest().name();
    }

    private static String reason(Throwable error) {
        if (error == null) {
            return "No error detail was provided by the tool.";
        }
        Set<String> messages = new LinkedHashSet<>();
        Throwable current = error;
        while (current != null && messages.size() < 3) {
            String message = clean(current.getMessage());
            if (message != null) {
                messages.add(message);
            }
            current = current.getCause();
        }
        String reason = messages.isEmpty()
                ? "No error detail was provided by the tool (" + error.getClass().getSimpleName() + ")."
                : String.join(" Caused by: ", messages);
        return reason.length() <= MAX_REASON_LENGTH ? reason : reason.substring(0, MAX_REASON_LENGTH) + "...(truncated)";
    }

    private static String clean(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        return message.strip().replaceAll("\\s+", " ");
    }
}
