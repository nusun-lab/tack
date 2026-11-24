package us.nusun.tack.framework.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record LlmJobDeadLetter(
        UUID jobId,
        String laneId,
        LlmPassType passType,
        String requester,
        String role,
        String model,
        Map<String, Object> metadata,
        String failureMessage,
        Instant requestedAt,
        Instant timestamp
) {
}
