package us.nusun.tack.framework.inference;

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
    private final ToolPassSettings settings;
    private long cumulativeEstimatedInputTokens;

    LlmCallDiagnostics(Exchange exchange, TokenCountEstimator tokenCountEstimator, ToolPassSettings settings) {
        this.exchange = exchange;
        this.invocation = LlmExecutionContext.require(exchange);
        this.tokenCountEstimator = tokenCountEstimator;
        this.settings = settings;
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

    ChatRequest projectRequest(ChatRequest request) {
        var projection = ToolTranscriptCompaction.project(request, settings, tokenCountEstimator);
        int input = projection.promptTokens() + projection.toolTokens() + projection.responseSchemaTokens()
                + projection.projectedTokens();
        cumulativeEstimatedInputTokens += input;
        LOG.info("[{}] TOKEN BUDGET jobId={} lane={} input={}/{} | prompt={} tools={} response={} "
                        + "| transcriptRaw={} transcriptProjected={} transcriptBudget={} "
                        + "| retainedResults={} omittedResults={} reclaimedTokens={} compacted={} cumulativeInput={}",
                invocation.logLabel(), invocation.jobId(), invocation.laneId(), input, projection.inputCeiling(),
                projection.promptTokens(), projection.toolTokens(), projection.responseSchemaTokens(),
                projection.rawTokens(), projection.projectedTokens(), projection.budget(),
                projection.retainedResults(), projection.omittedResults(),
                projection.rawTokens() - projection.projectedTokens(), projection.omittedResults() > 0,
                cumulativeEstimatedInputTokens);
        return projection.request();
    }
}
