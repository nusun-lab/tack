package us.nusun.llmshare.framework.model;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record LlmJobResult(
        UUID jobId,
        LlmJobStatus status,
        String laneId,
        LlmPassType passType,
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
        return new LlmJobResult(job.jobId(), LlmJobStatus.SUCCEEDED, job.laneId(), job.passType(), job.requester(), job.role(), model, content, rawResponse, job.requestedAt(), startedAt, completedAt, Duration.between(startedAt, completedAt).toMillis(), null);
    }

    public static LlmJobResult failed(LlmJobEnvelope job, String model, String error, String rawResponse, Instant startedAt) {
        Instant completedAt = Instant.now();
        return new LlmJobResult(job.jobId(), LlmJobStatus.FAILED, job == null ? null : job.laneId(), job == null ? null : job.passType(), job == null ? null : job.requester(), job == null ? null : job.role(), model, null, rawResponse, job == null ? null : job.requestedAt(), startedAt, completedAt, Duration.between(startedAt, completedAt).toMillis(), error);
    }
}
