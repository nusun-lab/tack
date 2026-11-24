package us.nusun.tack.framework.model;

public record DecisionResult(
        String decisionId,
        String result,
        double confidence,
        String reason
) {}
