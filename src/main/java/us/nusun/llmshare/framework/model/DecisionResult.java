package us.nusun.llmshare.framework.model;

public record DecisionResult(
        String decisionId,
        boolean result,
        double confidence,
        String reason
) {}
