package us.nusun.llmshare.model;

public record UserGoalRequest(
        String goal
) {
    public void validate() {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal is required");
        }
    }
}
