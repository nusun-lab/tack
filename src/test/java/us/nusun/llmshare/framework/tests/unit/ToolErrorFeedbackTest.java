package us.nusun.llmshare.framework.tests.unit;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolErrorContext;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.llm.ToolErrorFeedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolErrorFeedbackTest {

    @Test
    void executionFeedbackPreservesUsefulCauseAndDiscouragesBlindRetry() {
        RuntimeException error = new RuntimeException("MCP tool execution failed", new IllegalStateException("get dir content err: not found"));

        String feedback = ToolErrorFeedback.execution(error, context("gitea__get_dir_contents")).text();

        assertTrue(feedback.contains("Tool 'gitea__get_dir_contents' failed."));
        assertTrue(feedback.contains("MCP tool execution failed"));
        assertTrue(feedback.contains("get dir content err: not found"));
        assertTrue(feedback.contains("Do not repeat the same tool call unchanged"));
    }

    @Test
    void executionFeedbackMakesBlankErrorsActionable() {
        String feedback = ToolErrorFeedback.execution(new RuntimeException(), context("gitea__get_dir_contents")).text();

        assertTrue(feedback.contains("No error detail was provided by the tool (RuntimeException)."));
        assertTrue(feedback.contains("Do not repeat the same tool call unchanged"));
    }

    @Test
    void argumentFeedbackInvitesCorrectionInsteadOfAborting() {
        String feedback = ToolErrorFeedback.arguments(
                new IllegalArgumentException("missing required property 'path'"),
                context("gitea__get_dir_contents")).text();

        assertEquals(
                "Tool 'gitea__get_dir_contents' received invalid arguments. Reason: missing required property 'path' "
                        + "Correct the arguments to match the tool contract before retrying. Do not repeat the same invalid arguments unchanged.",
                feedback);
    }

    private static ToolErrorContext context(String toolName) {
        return ToolErrorContext.builder()
                .toolExecutionRequest(ToolExecutionRequest.builder()
                        .name(toolName)
                        .arguments("{}")
                        .build())
                .invocationContext(InvocationContext.builder().chatMemoryId("test").build())
                .build();
    }
}
