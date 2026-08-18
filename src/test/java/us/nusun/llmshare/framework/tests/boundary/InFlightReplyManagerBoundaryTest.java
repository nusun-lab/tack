package us.nusun.llmshare.framework.tests.boundary;

import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.llm.InFlightReplyManager;
import us.nusun.llmshare.framework.model.LlmJob;
import us.nusun.llmshare.framework.model.LlmJobResult;
import us.nusun.llmshare.framework.model.LlmJobStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InFlightReplyManagerBoundaryTest {
    @Test
    void successfulReplyCompletesAwaitAndCleansUpRegistration() throws Exception {
        InFlightReplyManager manager = new InFlightReplyManager();
        LlmJob job = queuedJob(UUID.randomUUID());
        LlmJobResult result = LlmJobResult.succeeded(job, "mock-model", "ok", Instant.now());

        assertSame(job, manager.register(job));
        assertEquals(1, manager.inFlightCount());
        manager.complete(result);

        assertEquals(result, manager.await(job.jobId(), 100));
        assertFalse(manager.contains(job.jobId()));
        assertEquals(0, manager.inFlightCount());
    }

    @Test
    void duplicateRegistrationForSameJobIdIsRejectedAndOriginalRegistrationRemains() {
        InFlightReplyManager manager = new InFlightReplyManager();
        UUID jobId = UUID.randomUUID();
        manager.register(queuedJob(jobId));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> manager.register(queuedJob(jobId)));

        assertTrue(failure.getMessage().contains("Duplicate in-flight jobId " + jobId));
        assertTrue(manager.contains(jobId));
        assertEquals(1, manager.inFlightCount());
    }

    @Test
    void completedReplyForUnknownJobIsIgnored() {
        InFlightReplyManager manager = new InFlightReplyManager();
        LlmJob job = queuedJob(UUID.randomUUID());
        LlmJobResult result = LlmJobResult.failed(job, "mock-model", "late reply", Instant.now());

        assertSame(result, manager.complete(result));
        assertEquals(0, manager.inFlightCount());
    }

    @Test
    void jobIdCanBeReusedAfterCleanup() throws Exception {
        InFlightReplyManager manager = new InFlightReplyManager();
        UUID jobId = UUID.randomUUID();
        LlmJob first = queuedJob(jobId);
        manager.register(first);
        manager.complete(LlmJobResult.succeeded(first, "mock-model", "first", Instant.now()));
        assertEquals("first", manager.await(jobId, 100).content());

        LlmJob second = queuedJob(jobId);
        manager.register(second);
        manager.complete(LlmJobResult.succeeded(second, "mock-model", "second", Instant.now()));

        assertEquals("second", manager.await(jobId, 100).content());
        assertEquals(0, manager.inFlightCount());
    }

    @Test
    void timeoutRemovesRegistrationAndLateReplyDoesNotRecreateIt() {
        InFlightReplyManager manager = new InFlightReplyManager();
        LlmJob job = queuedJob(UUID.randomUUID());
        manager.register(job);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> manager.await(job.jobId(), 1));
        manager.complete(new LlmJobResult(job.jobId(), LlmJobStatus.SUCCEEDED, job.laneId(), "mock", "late",
                job.requestedAt(), Instant.now(), Instant.now(), 0, null));

        assertTrue(failure.getMessage().contains("Timed out waiting for LLM reply"));
        assertFalse(manager.contains(job.jobId()));
        assertEquals(0, manager.inFlightCount());
    }

    private static LlmJob queuedJob(UUID jobId) {
        return new LlmJob(
                jobId,
                "low-pass",
                "prompt",
                "system",
                null,
                Map.of("laneId", "low-pass", "primitive", "act"),
                Instant.now());
    }
}
