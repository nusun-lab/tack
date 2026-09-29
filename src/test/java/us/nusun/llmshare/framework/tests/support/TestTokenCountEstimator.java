package us.nusun.llmshare.framework.tests.support;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;

/**
 * Deterministic offline token estimator for tests that exercise budget mechanics rather than tokenizer fidelity.
 */
public final class TestTokenCountEstimator implements TokenCountEstimator {
    @Override
    public int estimateTokenCountInText(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, (text.length() + 3) / 4);
    }

    @Override
    public int estimateTokenCountInMessage(ChatMessage message) {
        int tokens = 4;
        if (message instanceof SystemMessage system) {
            return tokens + estimateTokenCountInText(system.text());
        }
        if (message instanceof UserMessage user) {
            for (Content content : user.contents()) {
                if (content instanceof TextContent text) {
                    tokens += estimateTokenCountInText(text.text());
                }
            }
            return tokens;
        }
        if (message instanceof AiMessage ai) {
            tokens += estimateTokenCountInText(ai.text());
            tokens += estimateTokenCountInText(ai.thinking());
            for (ToolExecutionRequest request : ai.toolExecutionRequests()) {
                tokens += estimateTokenCountInText(request.name());
                tokens += estimateTokenCountInText(request.arguments());
            }
            return tokens;
        }
        if (message instanceof ToolExecutionResultMessage result) {
            return tokens + estimateTokenCountInText(result.text());
        }
        return tokens;
    }

    @Override
    public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
        int tokens = 3;
        for (ChatMessage message : messages) {
            tokens += estimateTokenCountInMessage(message);
        }
        return tokens;
    }
}
