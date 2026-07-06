package us.nusun.llmshare.framework.model;

import java.util.List;
import java.util.Map;

public record StepResult(
        String actId,
        String summary,
        List<Map<String, Object>> artifacts,
        List<ThoughtUpdate> thoughts
) {
    public StepResult {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        thoughts = thoughts == null ? List.of() : List.copyOf(thoughts);
    }
}
