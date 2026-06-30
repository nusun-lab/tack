package us.nusun.llmshare.framework.mcp;

import dev.langchain4j.service.tool.ToolExecutionResult;

final class McpToolResultLimiter {
    static final int DISABLED = 0;
    static final String TRUNCATION_MARKER_FORMAT = "...(truncated, original %d chars)";

    private McpToolResultLimiter() {
    }

    static int effectiveLimit(int profileMaxCharacters, int globalMaxCharacters) {
        return profileMaxCharacters > 0 ? profileMaxCharacters : globalMaxCharacters;
    }

    static ToolExecutionResult limit(ToolExecutionResult result, int maxCharacters) {
        String text = resultText(result);
        if (maxCharacters <= DISABLED || text == null || text.length() <= maxCharacters) {
            return result;
        }
        return ToolExecutionResult.builder()
                .isError(result.isError())
                .resultText(truncate(text, maxCharacters))
                .build();
    }

    static String truncate(String text, int maxCharacters) {
        String marker = TRUNCATION_MARKER_FORMAT.formatted(text.length());
        if (maxCharacters <= marker.length()) {
            return marker.substring(0, Math.max(0, maxCharacters));
        }
        return text.substring(0, maxCharacters - marker.length()) + marker;
    }

    static String resultText(ToolExecutionResult result) {
        try {
            return result.resultText();
        } catch (Exception ignored) {
            return String.valueOf(result);
        }
    }
}
