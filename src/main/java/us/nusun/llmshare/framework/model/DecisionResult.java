package us.nusun.llmshare.framework.model;

import java.util.List;

public record DecisionResult(
        String decisionId,
        boolean result,
        double confidence,
        String reason,
        List<String> evidence
) {
    public DecisionResult {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
