package us.nusun.tack.framework.model;

import java.util.List;
import java.util.Map;

public record StepResult(
        String actId,
        String thought,
        String summary,
        String outcome,
        boolean durableProgress,
        double confidence,
        List<Map<String, Object>> artifacts
) {
    public StepResult {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }

    public StepResult(String actId, String summary, List<Map<String, Object>> artifacts) {
        this(actId, null, summary, "SUCCEEDED", false, 0.0, artifacts);
    }
}
