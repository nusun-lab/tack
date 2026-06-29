package us.nusun.llmshare.framework.model;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record LlmJobResult(
        UUID jobId,
        LlmJobStatus status,
        String requester,
        String role,
        String model,
        String content,
        String rawResponse,
        Instant requestedAt,
        Instant startedAt,
        Instant completedAt,
        long latencyMs,
        String error
) {
    public static LlmJobResult succeeded(LlmJobEnvelope job, String model, String content, String rawResponse, Instant startedAt) {
        Instant completedAt = Instant.now();
        return new LlmJobResult(job.jobId(), LlmJobStatus.SUCCEEDED, job.requester(), job.role(), model, content, rawResponse, job.requestedAt(), startedAt, completedAt, Duration.between(startedAt, completedAt).toMillis(), null);
    }

    public static LlmJobResult failed(LlmJobEnvelope job, String model, String error, String rawResponse, Instant startedAt) {
        Instant completedAt = Instant.now();
        return new LlmJobResult(job.jobId(), LlmJobStatus.FAILED, job.requester(), job.role(), model, null, rawResponse, job.requestedAt(), startedAt, completedAt, Duration.between(startedAt, completedAt).toMillis(), error);
    }
}
