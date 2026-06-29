package us.nusun.llmshare.framework.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;

/**
 * Conservative fallback estimator for OpenAI-compatible models unknown to jtokkit.
 */
public final class ApproximateTokenCountEstimator implements TokenCountEstimator {

    @Override
    public int estimateTokenCountInMessage(ChatMessage message) {
        int tokenCount = 4;

        if (message instanceof SystemMessage systemMessage) {
            return tokenCount + estimateTokenCountInText(systemMessage.text());
        }
        if (message instanceof UserMessage userMessage) {
            return tokenCount + estimateUserMessageTokens(userMessage);
        }
        if (message instanceof AiMessage aiMessage) {
            return tokenCount + estimateAiMessageTokens(aiMessage);
        }
        if (message instanceof ToolExecutionResultMessage toolExecutionResultMessage) {
            return tokenCount + estimateTokenCountInText(toolExecutionResultMessage.text());
        }

        throw new IllegalArgumentException("Unknown message type: " + message);
    }

    @Override
    public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
        int tokenCount = 3;
        for (ChatMessage message : messages) {
            tokenCount += estimateTokenCountInMessage(message);
        }
        return tokenCount;
    }

    private int estimateUserMessageTokens(UserMessage userMessage) {
        int tokenCount = 0;
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

    private int estimateAiMessageTokens(AiMessage aiMessage) {
        int tokenCount = 0;
        if (aiMessage.text() != null) {
            tokenCount += estimateTokenCountInText(aiMessage.text());
        }
        if (aiMessage.hasToolExecutionRequests()) {
            tokenCount += 8;
            tokenCount += aiMessage.toolExecutionRequests().stream()
                    .mapToInt(request -> estimateTokenCountInText(request.name()) + estimateTokenCountInText(request.arguments()))
                    .sum();
        }
        return tokenCount;
    }

    @Override
    public int estimateTokenCountInText(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, (text.length() + 3) / 4);
    }
}
