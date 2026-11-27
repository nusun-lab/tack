package us.nusun.tack.framework.inference;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@ApplicationScoped
public class InFlightReplyManager {
    private static final Logger LOG = Logger.getLogger(InFlightReplyManager.class);
    private final Map<UUID, Pending> replies = new HashMap<>();

    void register(UUID jobId) {
        register(jobId, null);
    }

    synchronized void register(UUID jobId, String agentRunId) {
        if (replies.containsKey(jobId)) {
            throw new IllegalStateException("Duplicate in-flight jobId " + jobId);
        }
        if (agentRunId != null && replies.values().stream().anyMatch(reply -> agentRunId.equals(reply.agentRunId))) {
            throw new IllegalStateException("AgentRun already has an in-flight LLM job: " + agentRunId);
        }
        replies.put(jobId, new Pending(agentRunId));
    }

    synchronized boolean claim(UUID jobId) {
        Pending pending = replies.get(jobId);
        if (pending == null || pending.claimed || pending.result.isDone()) {
            return false;
        }
        pending.claimed = true;
        return true;
    }

    public LlmJobResult await(UUID jobId, long timeoutMs) throws Exception {
        Pending pending = pending(jobId);
        if (pending == null) {
            throw new IllegalStateException("No in-flight reply registered for " + jobId);
        }
        try {
            return pending.result.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            if (withdrawIfUnclaimed(jobId, pending)) {
                throw new IllegalStateException("Timed out waiting for LLM reply for " + jobId + " after " + timeoutMs
                        + "ms. The unclaimed request was withdrawn.", e);
            }
            LOG.warnf("LLM wait threshold exceeded; waiting for active execution to finish: jobId=%s agentRunId=%s waitMs=%d",
                    jobId, pending.agentRunId, timeoutMs);
            return pending.result.get();
        } finally {
            release(jobId, pending);
        }
    }

    public void discard(UUID jobId) {
        Pending pending = pending(jobId);
        if (pending != null) {
            release(jobId, pending);
        }
    }

    synchronized int inFlightCount() {
        return replies.size();
    }

    public void complete(LlmJobResult result) {
        Pending pending = pending(result.jobId());
        if (pending != null) {
            pending.result.complete(result);
        }
    }

    private synchronized Pending pending(UUID jobId) {
        return replies.get(jobId);
    }

    private synchronized boolean withdrawIfUnclaimed(UUID jobId, Pending pending) {
        if (pending.claimed) {
            return false;
        }
        replies.remove(jobId, pending);
        return true;
    }

    private void release(UUID jobId, Pending pending) {
        if (withdrawIfUnclaimed(jobId, pending)) {
            return;
        }
        // An interruption or ambiguous publish failure cannot release a live tool pass.
        pending.result.join();
        synchronized (this) {
            replies.remove(jobId, pending);
        }
    }

    private static final class Pending {
        private final String agentRunId;
        private final CompletableFuture<LlmJobResult> result = new CompletableFuture<>();
        private boolean claimed;

        private Pending(String agentRunId) {
            this.agentRunId = agentRunId;
        }
    }
}
