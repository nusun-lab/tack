package us.nusun.tack.framework.routine;

public record StepResult(
        String actId,
        String thought,
        String summary,
        String outcome,
        boolean durableProgress,
        double confidence
) implements PrimitiveResult {}
