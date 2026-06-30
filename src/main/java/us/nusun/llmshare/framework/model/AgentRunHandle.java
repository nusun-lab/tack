package us.nusun.llmshare.framework.model;

public record AgentRunHandle(
        String agentRunId,
        String missionId,
        String parentAgentRunId,
        String personaId,
        String routineId,
        String status
) {
}
