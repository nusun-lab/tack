package us.nusun.tack.framework.inference;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.request.ChatRequest;
import us.nusun.tack.framework.inference.lane.ToolPassSettings;
import us.nusun.tack.framework.inference.memory.ToolLoopGuardChatMemory.ToolPassTerminatedException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Fits each outbound request without altering the memory that owns the execution history. */
final class ToolTranscriptCompaction {
    private ToolTranscriptCompaction() {
    }

    static Projection project(ChatRequest request, ToolPassSettings settings, TokenCountEstimator estimator) {
        var raw = request.messages();
        int promptTokens = estimator.estimateTokenCountInMessages(protectedPrompts(raw));
        int toolTokens = request.toolSpecifications().stream().map(ToolSpecification::toJson)
                .mapToInt(estimator::estimateTokenCountInText).sum();
        int responseSchemaTokens = request.responseFormat() == null ? 0
                : estimator.estimateTokenCountInText(request.responseFormat().toString());
        int reservedOutput = request.maxOutputTokens() == null ? settings.maxOutputTokens() : request.maxOutputTokens();
        int inputCeiling = settings.contextWindow() - reservedOutput;
        int budget = Math.min(settings.memoryTokens(), inputCeiling - promptTokens - toolTokens - responseSchemaTokens);
        int rawTokens = estimator.estimateTokenCountInMessages(raw) - promptTokens;
        int resultCount = (int) raw.stream().filter(ToolExecutionResultMessage.class::isInstance).count();
        if (rawTokens <= budget) {
            return new Projection(request, inputCeiling, promptTokens, toolTokens, responseSchemaTokens, budget,
                    rawTokens, rawTokens, resultCount, 0);
        }

        var projected = new ArrayList<>(raw);
        int projectedTokens = rawTokens;
        int omitted = 0;
        int target = (int) (budget * 9L / 10);
        for (Candidate candidate : candidates(raw, estimator)) {
            projected.set(candidate.index(), candidate.notice());
            omitted++;
            projectedTokens = estimator.estimateTokenCountInMessages(projected) - promptTokens;
            if (projectedTokens <= target) {
                break;
            }
        }
        if (projectedTokens > budget) {
            throw new ContextBudgetExceededException(budget, projectedTokens);
        }
        return new Projection(request.toBuilder().messages(projected).build(), inputCeiling, promptTokens, toolTokens,
                responseSchemaTokens, budget, rawTokens, projectedTokens, resultCount - omitted, omitted);
    }

    private static List<ChatMessage> protectedPrompts(List<ChatMessage> messages) {
        var prompts = new ArrayList<ChatMessage>(2);
        SystemMessage.findFirst(messages).ifPresent(prompts::add);
        messages.stream().filter(UserMessage.class::isInstance).findFirst().ifPresent(prompts::add);
        return prompts;
    }

    private static List<Candidate> candidates(List<ChatMessage> messages, TokenCountEstimator estimator) {
        var candidates = new ArrayList<Candidate>();
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof ToolExecutionResultMessage result) {
                var notice = omittedResult(result);
                int savings = estimator.estimateTokenCountInMessage(result) - estimator.estimateTokenCountInMessage(notice);
                if (savings > 0) {
                    candidates.add(new Candidate(i, notice, Integer.highestOneBit(savings)));
                }
            }
        }
        // Remove large payloads first and prefer older evidence for savings of similar magnitude.
        candidates.sort(Comparator.comparingInt(Candidate::savingsTier).reversed().thenComparingInt(Candidate::index));
        return candidates;
    }

    private static ToolExecutionResultMessage omittedResult(ToolExecutionResultMessage result) {
        String outcome = result.isError() == null ? "Tool completed. Outcome unknown."
                : result.isError() ? "Tool returned an error." : "Tool completed without a reported error.";
        return ToolExecutionResultMessage.builder()
                .id(result.id()).toolName(result.toolName()).isError(result.isError()).attributes(result.attributes())
                .text(outcome + " Output omitted to fit the model context. Do not repeat the call just to recover its output.")
                .build();
    }

    private record Candidate(int index, ToolExecutionResultMessage notice, int savingsTier) {}

    record Projection(ChatRequest request, int inputCeiling, int promptTokens, int toolTokens, int responseSchemaTokens,
                      int budget, int rawTokens, int projectedTokens, int retainedResults, int omittedResults) {}

    private static final class ContextBudgetExceededException extends ToolPassTerminatedException {
        private ContextBudgetExceededException(int budget, int required) {
            super("TACK_RUNTIME_CONTEXT_BUDGET_EXHAUSTED: The tool pass stopped because the context has room for "
                    + Math.max(0, budget) + " transcript tokens, and " + required
                    + " remain after omitting tool payloads. Verify external state before retrying any completed operation.");
        }
    }
}
