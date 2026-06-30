package us.nusun.llmshare.framework.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.mcp.client.DefaultMcpToolResultExtractor;
import dev.langchain4j.mcp.client.McpToolResultExtractor;
import dev.langchain4j.service.tool.ToolExecutionResult;

/**
 * @deprecated Runtime MCP result limiting is enforced by {@link ProfiledMcpClient}.
 * This extractor remains only as a compatibility wrapper for callers that still
 * configure a LangChain4j extractor directly.
 */
@Deprecated(forRemoval = false)
public final class BoundedMcpToolResultExtractor implements McpToolResultExtractor {
    static final int DEFAULT_MAX_CHARACTERS = 1000;

    private final int maxCharacters;
    private final DefaultMcpToolResultExtractor delegate = new DefaultMcpToolResultExtractor();

    public BoundedMcpToolResultExtractor(int maxCharacters) {
        this.maxCharacters = maxCharacters > 0 ? maxCharacters : DEFAULT_MAX_CHARACTERS;
    }

    @Override
    public ToolExecutionResult extract(JsonNode content, boolean isError) {
        ToolExecutionResult result = delegate.extract(content, isError);
        return McpToolResultLimiter.limit(result, maxCharacters);
    }
}
