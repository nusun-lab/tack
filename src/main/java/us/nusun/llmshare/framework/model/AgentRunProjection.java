package us.nusun.llmshare.framework.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AgentRunProjection(
        String agentRunId,
        String missionId,
        String parentAgentRunId,
        String status,
        Map<String, Object> mission,
        Map<String, Object> persona,
        Map<String, Object> routine,
        Map<String, Object> subroutine,
        Object input,
        Map<String, Object> current,
        String toolProfile,
        String laneId,
        List<Map<String, Object>> thoughtStack,
        List<Map<String, Object>> thoughtHistory,
        Map<String, Object> latestResult,
        List<Map<String, Object>> timeline,
        Instant updatedAt
) {
    public AgentRunProjection(
            String agentRunId,
            String missionId,
            String parentAgentRunId,
            String status,
            Map<String, Object> persona,
            Map<String, Object> routine,
            Map<String, Object> current,
            String toolProfile,
            String laneId,
            List<Map<String, Object>> thoughtStack,
            List<Map<String, Object>> thoughtHistory,
            Map<String, Object> latestResult,
            List<Map<String, Object>> timeline,
            Instant updatedAt) {
        this(
                agentRunId,
                missionId,
                parentAgentRunId,
                status,
                missionId == null ? Map.of() : Map.of("id", missionId),
                persona,
                routine,
                Map.of(),
                null,
                current,
                toolProfile,
                laneId,
                thoughtStack,
                thoughtHistory,
                latestResult,
                timeline,
                updatedAt);
    }
}
