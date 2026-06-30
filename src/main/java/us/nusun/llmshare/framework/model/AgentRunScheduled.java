package us.nusun.llmshare.framework.model;

public record AgentRunScheduled(
        String missionId,
        String agentRunId
) {
    public AgentRunScheduled {
        if (missionId == null || missionId.isBlank()) {
            throw new IllegalArgumentException("AgentRunScheduled missing missionId");
        }
        if (agentRunId == null || agentRunId.isBlank()) {
            throw new IllegalArgumentException("AgentRunScheduled missing agentRunId");
        }
    }
}
