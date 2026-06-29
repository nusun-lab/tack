package us.nusun.llmshare.framework.model;

import java.util.List;
import java.util.Map;

public record StepResult(
        String actId,
        String status,
        String summary,
        List<Map<String, Object>> observations,
        List<Map<String, Object>> actions,
        List<Map<String, Object>> artifacts,
        List<Map<String, Object>> requestedActions,
        List<ThoughtUpdate> thoughts,
        String nextStepRecommendation
) {
    public StepResult {
        observations = observations == null ? List.of() : List.copyOf(observations);
        actions = actions == null ? List.of() : List.copyOf(actions);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        requestedActions = requestedActions == null ? List.of() : List.copyOf(requestedActions);
        thoughts = thoughts == null ? List.of() : List.copyOf(thoughts);
    }
}
