package us.nusun.tack.framework.run;

import us.nusun.tack.framework.routine.ThoughtFrame;
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
        Map<String, Object> input,
        Map<String, Object> current,
        String toolProfile,
        String laneId,
        List<ThoughtFrame> thoughtStack,
        List<Map<String, Object>> thoughtHistory,
        Map<String, Object> latestResult,
        List<Map<String, Object>> timeline,
        Instant updatedAt
) {}
