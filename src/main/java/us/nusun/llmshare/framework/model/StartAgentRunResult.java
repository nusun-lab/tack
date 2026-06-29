package us.nusun.llmshare.framework.model;

public record StartAgentRunResult(
        String agentRunId,
        String missionId,
        String parentAgentRunId,
        String status
) {
}
