package us.nusun.tack.framework.inference;

import us.nusun.tack.framework.JsonValues;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
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
    public LlmJobEnvelope {
        // Admission assigns a status and takes the immutable Kafka snapshot.
        metadata = status == null ? Objects.requireNonNullElse(metadata, Map.of()) : JsonValues.snapshot(metadata);
    }

    public LlmJobEnvelope(UUID jobId, String laneId, LlmPassType passType, String requester, String role, int priority,
                          String prompt, String systemPrompt, String responseSchema,
                          Map<String, Object> metadata, Instant requestedAt) {
        this(jobId, null, laneId, passType, requester, role, priority, prompt, systemPrompt, responseSchema, metadata,
                requestedAt);
    }

    public boolean hasResponseSchema() {
        return responseSchema != null && !responseSchema.isBlank();
    }
}
