package us.nusun.tack.framework.llm;

import static us.nusun.tack.framework.model.FrameworkText.firstNonBlank;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.service.Result;
import org.apache.camel.Exchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Measurements from one tool-capable model invocation. */
final class LlmCallDiagnostics {
    private static final Logger LOG = LoggerFactory.getLogger(LlmCallDiagnostics.class);

    private final Exchange exchange;
    private final TokenCountEstimator tokenCountEstimator;
    private final int contextWindow;
    private final int maxOutputTokens;
    private final int transcriptMaxTokens;
    private long cumulativeEstimatedInputTokens;

    LlmCallDiagnostics(Exchange exchange, TokenCountEstimator tokenCountEstimator,
                       int contextWindow, int maxOutputTokens, int transcriptMaxTokens) {
        this.exchange = exchange;
        this.tokenCountEstimator = tokenCountEstimator;
        this.contextWindow = contextWindow;
        this.maxOutputTokens = maxOutputTokens;
        this.transcriptMaxTokens = transcriptMaxTokens;
    }

    void capture(Result<String> result) {
        var finalResponse = result == null ? null : result.finalResponse();
        var aiMessage = finalResponse == null ? null : finalResponse.aiMessage();
        String text = aiMessage == null ? null : aiMessage.text();
        String thinking = aiMessage == null ? null : aiMessage.thinking();

        exchange.setProperty("llmFinalTextChars", text == null ? 0 : text.length());
        exchange.setProperty("llmFinalThinkingChars", thinking == null ? 0 : thinking.length());
        exchange.setProperty("llmIntermediateResponses",
                result == null || result.intermediateResponses() == null ? 0 : result.intermediateResponses().size());
        exchange.setProperty("llmToolExecutions",
                result == null || result.toolExecutions() == null ? 0 : result.toolExecutions().size());

        if (finalResponse != null && finalResponse.tokenUsage() != null) {
            exchange.setProperty("llmFinalInputTokens", finalResponse.tokenUsage().inputTokenCount());
            exchange.setProperty("llmFinalOutputTokens", finalResponse.tokenUsage().outputTokenCount());
            exchange.setProperty("llmFinalTotalTokens", finalResponse.tokenUsage().totalTokenCount());
            if (finalResponse.tokenUsage() instanceof OpenAiTokenUsage openAiTokenUsage
                    && openAiTokenUsage.outputTokensDetails() != null) {
                exchange.setProperty("llmFinalReasoningTokens",
                        openAiTokenUsage.outputTokensDetails().reasoningTokens());
            }
        }

        LOG.info("LLM final response diagnostics: lane={} passType={} textChars={} thinkingChars={} "
                        + "finalInputTokens={} finalOutputTokens={} finalReasoningTokens={} "
                        + "intermediateResponses={} toolExecutions={}",
                exchange.getProperty("laneId"),
                exchange.getProperty("passType"),
                exchange.getProperty("llmFinalTextChars"),
                exchange.getProperty("llmFinalThinkingChars"),
                exchange.getProperty("llmFinalInputTokens"),
                exchange.getProperty("llmFinalOutputTokens"),
                exchange.getProperty("llmFinalReasoningTokens"),
                exchange.getProperty("llmIntermediateResponses"),
                exchange.getProperty("llmToolExecutions"));
    }

    void logBudget(ChatRequest request) {
        int promptTokens = 0;
        int messageTokens = 0;
        int thinkingTokens = 0;
        int toolCallTokens = 0;
        int toolResultTokens = 0;
        boolean firstUser = true;

        for (ChatMessage message : request.messages()) {
            if (message instanceof SystemMessage || (firstUser && message instanceof UserMessage)) {
                promptTokens += tokenCountEstimator.estimateTokenCountInMessage(message);
                if (message instanceof UserMessage) firstUser = false;
            } else if (message instanceof UserMessage) {
                firstUser = false;
                messageTokens += tokenCountEstimator.estimateTokenCountInMessage(message);
            } else if (message instanceof AiMessage ai) {
                messageTokens += tokenCountEstimator.estimateTokenCountInText(ai.text());
                thinkingTokens += tokenCountEstimator.estimateTokenCountInText(ai.thinking());
                if (ai.hasToolExecutionRequests()) {
                    for (var call : ai.toolExecutionRequests()) {
                        toolCallTokens += tokenCountEstimator.estimateTokenCountInText(call.name());
                        toolCallTokens += tokenCountEstimator.estimateTokenCountInText(call.arguments());
                    }
                }
            } else if (message instanceof ToolExecutionResultMessage) {
                toolResultTokens += tokenCountEstimator.estimateTokenCountInMessage(message);
            }
        }

        int toolSchemaTokens = request.toolSpecifications().stream()
                .map(ToolSpecification::toJson)
                .mapToInt(tokenCountEstimator::estimateTokenCountInText)
                .sum();
        int responseTokens = request.responseFormat() == null
                ? 0 : tokenCountEstimator.estimateTokenCountInText(String.valueOf(request.responseFormat()));
        int transcriptTokens = messageTokens + thinkingTokens + toolCallTokens + toolResultTokens;
        int inputTokens = promptTokens + toolSchemaTokens + responseTokens + transcriptTokens;
        boolean compacted = request.messages().stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .anyMatch(message -> "tack-runtime".equals(message.name()));
        int inputCeiling = Math.max(0, contextWindow - maxOutputTokens);
        cumulativeEstimatedInputTokens += inputTokens;

        String label = firstNonBlank(exchange.getProperty("agentLabel", String.class), "LLM");
        LOG.info("[{}] TOKEN BUDGET lane={} input={}/{} ({}%) | prompt={} tools={} response={} transcript={}/{} ({}%) "
                        + "| transcriptParts messages={} thinking={} toolCalls={} toolResults={} | compacted={} cumulativeInput={}",
                label,
                exchange.getProperty("laneId"),
                inputTokens,
                inputCeiling,
                percent(inputTokens, inputCeiling),
                promptTokens,
                toolSchemaTokens,
                responseTokens,
                transcriptTokens,
                transcriptMaxTokens,
                percent(transcriptTokens, transcriptMaxTokens),
                messageTokens,
                thinkingTokens,
                toolCallTokens,
                toolResultTokens,
                compacted,
                cumulativeEstimatedInputTokens);
    }

    private static int percent(long used, long max) {
        return max <= 0 ? 0 : (int) Math.round((double) used / (double) max * 100.0);
    }
}
