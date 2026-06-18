package us.nusun.llmshare.model;

import java.util.List;

public record RoutineStepResult(
        String role,
        String step,
        String routine,
        String summary,
        String nextAction,
        List<String> actionsTaken,
        List<String> decisions,
        List<String> openQuestions,
        List<String> artifactsTouched,
        boolean needsFollowup,
        boolean doneCandidate
) {
}
