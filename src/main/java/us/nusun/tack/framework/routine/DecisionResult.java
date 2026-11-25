package us.nusun.tack.framework.routine;

public record DecisionResult(
        String decisionId,
        String result,
        double confidence,
        String reason
) implements PrimitiveResult {}
