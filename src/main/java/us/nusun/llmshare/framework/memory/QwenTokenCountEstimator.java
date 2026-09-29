package us.nusun.llmshare.framework.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;

import java.nio.charset.StandardCharsets;

/**
 * Lightweight token estimator for Qwen-oriented budgeting.
 *
 * <p>This deliberately avoids model-specific tokenizer assets, native libraries, and network access.
 * UTF-8 bytes divided by three is a conservative approximation for the English, code, JSON, and
 * mixed-Unicode content Tack typically sends. Provider-reported token usage remains the source of
 * truth for calibration; this estimator is for runtime budgeting and relative accounting.</p>
 */
public final class QwenTokenCountEstimator implements TokenCountEstimator {
    private static final int BYTES_PER_TOKEN = 3;
    private static final int MESSAGE_OVERHEAD_TOKENS = 4;
    private static final int MESSAGE_LIST_OVERHEAD_TOKENS = 3;
    private static final int TOOL_REQUEST_OVERHEAD_TOKENS = 8;

    @Override
    public int estimateTokenCountInText(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        return (bytes + BYTES_PER_TOKEN - 1) / BYTES_PER_TOKEN;
    }

    @Override
    public int estimateTokenCountInMessage(ChatMessage message) {
        int tokenCount = MESSAGE_OVERHEAD_TOKENS;
        if (message instanceof SystemMessage systemMessage) {
            return tokenCount + estimateTokenCountInText(systemMessage.text());
        }
        if (message instanceof UserMessage userMessage) {
            for (Content content : userMessage.contents()) {
                if (content instanceof TextContent textContent) {
                    tokenCount += estimateTokenCountInText(textContent.text());
                }
            }
            if (userMessage.name() != null && !userMessage.name().isBlank()) {
                tokenCount += estimateTokenCountInText(userMessage.name());
            }
            return tokenCount;
        }
        if (message instanceof AiMessage aiMessage) {
            tokenCount += estimateTokenCountInText(aiMessage.text());
            tokenCount += estimateTokenCountInText(aiMessage.thinking());
            if (aiMessage.hasToolExecutionRequests()) {
                tokenCount += TOOL_REQUEST_OVERHEAD_TOKENS;
                for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                    tokenCount += estimateTokenCountInText(request.name());
                    tokenCount += estimateTokenCountInText(request.arguments());
                }
            }
            return tokenCount;
        }
        if (message instanceof ToolExecutionResultMessage result) {
            return tokenCount + estimateTokenCountInText(result.text());
        }
        throw new IllegalArgumentException("Unknown message type: " + message);
    }

    @Override
    public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
        int tokenCount = MESSAGE_LIST_OVERHEAD_TOKENS;
        for (ChatMessage message : messages) {
            tokenCount += estimateTokenCountInMessage(message);
        }
        return tokenCount;
    }
}
