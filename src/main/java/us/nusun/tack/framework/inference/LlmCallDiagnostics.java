package us.nusun.tack.framework.inference;

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
import us.nusun.tack.framework.inference.lane.ToolPassSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Measurements from one tool-capable model invocation. */
final class LlmCallDiagnostics {
    static final String FINAL_SUMMARY = "llmFinalDiagnostics";
    private static final Logger LOG = LoggerFactory.getLogger(LlmCallDiagnostics.class);

    private final Exchange exchange;
    private final LlmExecutionContext invocation;
    private final TokenCountEstimator tokenCountEstimator;
    private final int contextWindow;
    private final int maxOutputTokens;
    private final int transcriptMaxTokens;
    private long cumulativeEstimatedInputTokens;

    LlmCallDiagnostics(Exchange exchange, TokenCountEstimator tokenCountEstimator, ToolPassSettings settings) {
        this.exchange = exchange;
        this.invocation = LlmExecutionContext.require(exchange);
        this.tokenCountEstimator = tokenCountEstimator;
        this.contextWindow = settings.contextWindow();
        this.maxOutputTokens = settings.maxOutputTokens();
        this.transcriptMaxTokens = settings.memoryTokens();
    }

    void capture(Result<String> result) {
        var finalResponse = result == null ? null : result.finalResponse();
        var aiMessage = finalResponse == null ? null : finalResponse.aiMessage();
        String text = aiMessage == null ? null : aiMessage.text();
        String thinking = aiMessage == null ? null : aiMessage.thinking();

        var usage = finalResponse == null ? null : finalResponse.tokenUsage();
        Integer reasoning = usage instanceof OpenAiTokenUsage openAi && openAi.outputTokensDetails() != null
                ? openAi.outputTokensDetails().reasoningTokens() : null;
        String summary = ("finalTextChars=%d finalThinkingChars=%d finalInputTokens=%s finalOutputTokens=%s "
                + "finalReasoningTokens=%s intermediateResponses=%d toolExecutions=%d").formatted(
                text == null ? 0 : text.length(), thinking == null ? 0 : thinking.length(),
                usage == null ? null : usage.inputTokenCount(), usage == null ? null : usage.outputTokenCount(), reasoning,
                result == null || result.intermediateResponses() == null ? 0 : result.intermediateResponses().size(),
                result == null || result.toolExecutions() == null ? 0 : result.toolExecutions().size());
        exchange.setProperty(FINAL_SUMMARY, summary);
        LOG.info("LLM final response diagnostics: lane={} passType={} {}", invocation.laneId(), invocation.passType(), summary);
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

        LOG.info("[{}] TOKEN BUDGET lane={} input={}/{} ({}%) | prompt={} tools={} response={} transcript={}/{} ({}%) "
                        + "| transcriptParts messages={} thinking={} toolCalls={} toolResults={} | compacted={} cumulativeInput={}",
                invocation.logLabel(),
                invocation.laneId(),
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
