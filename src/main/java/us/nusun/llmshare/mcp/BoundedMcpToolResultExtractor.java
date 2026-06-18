package us.nusun.llmshare.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.mcp.client.DefaultMcpToolResultExtractor;
import dev.langchain4j.mcp.client.McpToolResultExtractor;
import dev.langchain4j.service.tool.ToolExecutionResult;

public final class BoundedMcpToolResultExtractor implements McpToolResultExtractor {
    static final int DEFAULT_MAX_CHARACTERS = 1000;
    static final String SUPPRESSED_MESSAGE =
            "Tool result suppressed by policy because it exceeded the maximum allowed size. "
                    + "Make a more precise tool call with a smaller result.";

    private final int maxCharacters;
    private final DefaultMcpToolResultExtractor delegate = new DefaultMcpToolResultExtractor();

    public BoundedMcpToolResultExtractor(int maxCharacters) {
        this.maxCharacters = maxCharacters > 0 ? maxCharacters : DEFAULT_MAX_CHARACTERS;
    }

    @Override
    public ToolExecutionResult extract(JsonNode content, boolean isError) {
        ToolExecutionResult result = delegate.extract(content, isError);
        String resultText = result.resultText();
        if (resultText != null && resultText.length() > maxCharacters) {
            return ToolExecutionResult.builder()
                    .isError(true)
                    .resultText(SUPPRESSED_MESSAGE)
                    .build();
        }
        return result;
    }
}
