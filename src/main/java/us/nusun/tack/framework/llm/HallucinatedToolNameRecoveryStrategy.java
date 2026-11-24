package us.nusun.tack.framework.llm;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

public final class HallucinatedToolNameRecoveryStrategy implements Function<ToolExecutionRequest, ToolExecutionResultMessage> {
    private static final Logger LOG = LoggerFactory.getLogger(HallucinatedToolNameRecoveryStrategy.class);

    @Override
    public ToolExecutionResultMessage apply(ToolExecutionRequest request) {
        String toolName = request == null ? "" : request.name();
        LOG.warn("Recovering from hallucinated tool name: {}", toolName);
        return ToolExecutionResultMessage.builder()
                .id(request == null ? null : request.id())
                .toolName(toolName)
                .text(message(toolName))
                .isError(true)
                .build();
    }

    static String message(String toolName) {
        return """
                No tool named '%s' is available in the current tool profile.
                Do not retry this exact tool name during this pass.
                Use only the currently available tools. Choose another approach or continue without this operation.
                """.formatted(toolName).strip();
    }
}
