package us.nusun.llmshare.framework.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AgentRunProjection(
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
        Map<String, Object> latestResult,
        List<Map<String, Object>> timeline,
        Instant updatedAt
) {
}
