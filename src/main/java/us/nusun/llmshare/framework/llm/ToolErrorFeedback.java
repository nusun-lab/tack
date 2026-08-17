package us.nusun.llmshare.framework.llm;

import dev.langchain4j.service.tool.ToolErrorContext;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Converts tool failures into concise, actionable feedback that LangChain4j sends back to the model.
 */
public final class ToolErrorFeedback {
    private static final int MAX_REASON_LENGTH = 1500;

    private ToolErrorFeedback() {
    }

    public static ToolErrorHandlerResult execution(Throwable error, ToolErrorContext context) {
        return ToolErrorHandlerResult.text("Tool '" + toolName(context) + "' failed. Reason: " + reason(error)
                + " Use this error to correct the next action. Do not repeat the same tool call unchanged unless the error indicates that retrying may succeed.");
    }

    public static ToolErrorHandlerResult arguments(Throwable error, ToolErrorContext context) {
        return ToolErrorHandlerResult.text("Tool '" + toolName(context) + "' received invalid arguments. Reason: " + reason(error)
                + " Correct the arguments to match the tool contract before retrying. Do not repeat the same invalid arguments unchanged.");
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
