package us.nusun.tack.framework.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record LlmJobEnvelope(
        UUID jobId,
        LlmJobStatus status,
        String laneId,
        LlmPassType passType,
        String requester,
        String role,
        int priority,
        String prompt,
        String systemPrompt,
        String responseSchema,
        Map<String, Object> metadata,
        Instant requestedAt
) {
    public static LlmJobEnvelope queued(LlmJobRequest request) {
        LlmJobRequest normalized = request.normalized();
        return new LlmJobEnvelope(normalized.jobId(), LlmJobStatus.QUEUED, normalized.laneId(), normalized.passType(), normalized.requester(), normalized.role(), normalized.priority(), normalized.prompt(), normalized.systemPrompt(), normalized.responseSchema(), normalized.metadata(), normalized.requestedAt());
    }
}
