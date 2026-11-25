package us.nusun.tack.framework.inference;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@ApplicationScoped
@Named("inFlightReplyManager")
public class InFlightReplyManager {
    private final ConcurrentMap<UUID, CompletableFuture<LlmJobResult>> replies = new ConcurrentHashMap<>();

    public LlmJobEnvelope register(LlmJobEnvelope job) {
        CompletableFuture<LlmJobResult> previous = replies.putIfAbsent(job.jobId(), new CompletableFuture<>());
        if (previous != null) {
            throw new IllegalStateException("Duplicate in-flight jobId " + job.jobId());
        }
        return job;
    }

    public LlmJobResult await(UUID jobId, long timeoutMs) throws Exception {
        CompletableFuture<LlmJobResult> future = replies.get(jobId);
        if (future == null) {
            throw new IllegalStateException("No in-flight reply registered for " + jobId);
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Timed out waiting for LLM reply for " + jobId + " after " + timeoutMs + "ms", e);
        } finally {
            replies.remove(jobId);
        }
    }

    public void discard(UUID jobId) {
        if (jobId != null) {
            replies.remove(jobId);
        }
    }

    public boolean contains(UUID jobId) {
        return replies.containsKey(jobId);
    }

    public int inFlightCount() {
        return replies.size();
    }

    public LlmJobResult complete(LlmJobResult result) {
        CompletableFuture<LlmJobResult> future = replies.get(result.jobId());
        if (future != null) {
            future.complete(result);
        }
        return result;
    }
}
