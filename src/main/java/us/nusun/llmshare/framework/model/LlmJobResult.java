package us.nusun.llmshare.framework.model;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record LlmJobResult(
        UUID jobId,
        LlmJobStatus status,
        String laneId,
        String model,
        String content,
        Instant requestedAt,
        Instant startedAt,
        Instant completedAt,
        long latencyMs,
        String error
) {
    public static LlmJobResult succeeded(LlmJob job, String model, String content, Instant startedAt) {
        Instant completedAt = Instant.now();
        return new LlmJobResult(job.jobId(), LlmJobStatus.SUCCEEDED, job.laneId(), model, content, job.requestedAt(), startedAt, completedAt, Duration.between(startedAt, completedAt).toMillis(), null);
    }

    public static LlmJobResult failed(LlmJob job, String model, String error, Instant startedAt) {
        Instant completedAt = Instant.now();
        return new LlmJobResult(job == null ? null : job.jobId(), LlmJobStatus.FAILED, job == null ? null : job.laneId(), model, null, job == null ? null : job.requestedAt(), startedAt, completedAt, Duration.between(startedAt, completedAt).toMillis(), error);
    }
}
