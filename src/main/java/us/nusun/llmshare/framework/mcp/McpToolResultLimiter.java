package us.nusun.llmshare.framework.mcp;

import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.service.tool.ToolExecutionResult;

final class McpToolResultLimiter {
    static final int DISABLED = 0;
    static final String TRUNCATION_MARKER_FORMAT = "...(truncated, original %d tokens)";

    private McpToolResultLimiter() {
    }

    static int effectiveLimit(int profileMaxTokens, int globalMaxTokens) {
        return profileMaxTokens > 0 ? profileMaxTokens : globalMaxTokens;
    }

    static ToolExecutionResult limit(ToolExecutionResult result, int maxTokens, TokenCountEstimator tokenCountEstimator) {
        String text = resultText(result);
        if (maxTokens <= DISABLED || text == null) {
            return result;
        }
        TokenCountEstimator estimator = estimator(tokenCountEstimator);
        if (estimator.estimateTokenCountInText(text) <= maxTokens) {
            return result;
        }
        return ToolExecutionResult.builder()
                .isError(result.isError())
                .resultText(truncate(text, maxTokens, estimator))
                .build();
    }

    static String truncate(String text, int maxTokens, TokenCountEstimator tokenCountEstimator) {
        TokenCountEstimator estimator = estimator(tokenCountEstimator);
        if (maxTokens <= DISABLED || text == null || text.isEmpty()) {
            return "";
        }
        int originalTokens = estimator.estimateTokenCountInText(text);
        String marker = TRUNCATION_MARKER_FORMAT.formatted(originalTokens);
        if (estimator.estimateTokenCountInText(marker) > maxTokens) {
            return truncateToTokenBudget(marker, maxTokens, estimator);
        }

        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) / 2;
            String candidate = text.substring(0, mid) + marker;
            if (estimator.estimateTokenCountInText(candidate) <= maxTokens) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return text.substring(0, low) + marker;
    }

    static String resultText(ToolExecutionResult result) {
        try {
            return result.resultText();
        } catch (Exception ignored) {
            return String.valueOf(result);
        }
    }

    private static String truncateToTokenBudget(String text, int maxTokens, TokenCountEstimator estimator) {
        if (maxTokens <= DISABLED || text == null || text.isEmpty()) {
            return "";
        }
        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) / 2;
            if (estimator.estimateTokenCountInText(text.substring(0, mid)) <= maxTokens) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return text.substring(0, low);
    }

    private static TokenCountEstimator estimator(TokenCountEstimator tokenCountEstimator) {
        if (tokenCountEstimator == null) {
            throw new IllegalStateException("TokenCountEstimator is required when MCP tool-result token limiting is enabled");
        }
        return tokenCountEstimator;
    }
}
