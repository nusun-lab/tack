package us.nusun.tack.framework.inference;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolErrorContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolErrorFeedbackTest {

    @Test
    void executionFeedbackPreservesUsefulCause() {
        RuntimeException error = new RuntimeException("MCP tool execution failed", new IllegalStateException("get dir content err: not found"));

        String feedback = ToolErrorFeedback.execution(error, context("catalog__get_dir_contents")).text();

        assertEquals(
                "Tool 'catalog__get_dir_contents' failed. Reason: MCP tool execution failed Caused by: get dir content err: not found",
                feedback);
    }

    @Test
    void executionFeedbackHandlesBlankErrors() {
        String feedback = ToolErrorFeedback.execution(new RuntimeException(), context("catalog__get_dir_contents")).text();

        assertEquals(
                "Tool 'catalog__get_dir_contents' failed. Reason: No error detail was provided by the tool (RuntimeException).",
                feedback);
    }

    @Test
    void argumentFeedbackReturnsValidationFailure() {
        String feedback = ToolErrorFeedback.arguments(
                new IllegalArgumentException("missing required property 'path'"),
                context("catalog__get_dir_contents")).text();

        assertEquals(
                "Tool 'catalog__get_dir_contents' received invalid arguments. Reason: missing required property 'path'",
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
