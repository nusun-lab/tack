package us.nusun.llmshare.framework.tests.boundary;

import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.llm.KafkaTopicProvisioner;
import us.nusun.llmshare.framework.model.AgentRunProjection;
import us.nusun.llmshare.framework.model.Mission;
import us.nusun.llmshare.framework.projection.ProjectionStore;
import us.nusun.llmshare.framework.service.ResetService;
import us.nusun.llmshare.framework.state.AgentRun;
import us.nusun.llmshare.framework.state.AgentRunStore;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResetServiceBoundaryTest {
    @Test
    void resetPurgesKafkaTopicsAndClearsInMemoryState() throws Exception {
        ProjectionStore projections = new ProjectionStore();
        AgentRunStore agentRuns = new AgentRunStore();
        agentRuns.putMission(new Mission("mission-1", "Build", List.of(), "active", Map.of(), Instant.now()));
        agentRuns.putRun(new AgentRun("run-1", "mission-1", FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A, Map.of()));
        projections.put(projection("run-1"));
        TestKafkaTopicProvisioner kafkaTopics = new TestKafkaTopicProvisioner(List.of("agent-runs.scheduled", "llm.requests.low"));
        ResetService reset = resetService(projections, agentRuns, kafkaTopics);

        Map<String, Object> response = reset.reset();

        assertEquals("RESET", response.get("status"));
        assertEquals(List.of("agent-runs.scheduled", "llm.requests.low"), response.get("kafkaTopicsPurged"));
        assertEquals(1, kafkaTopics.purgeCalls);
        assertEquals(List.of(), agentRuns.missions());
        assertEquals(List.of(), agentRuns.runs());
        assertEquals(List.of(), projections.all());
    }

    @Test
    void resetRequestIncludesRequestIdAfterKafkaPurge() throws Exception {
        TestKafkaTopicProvisioner kafkaTopics = new TestKafkaTopicProvisioner(List.of("llm.requests.low"));
        ResetService reset = resetService(new ProjectionStore(), new AgentRunStore(), kafkaTopics);

        Map<String, Object> response = reset.resetRequest("request-1");

        assertEquals("RESET", response.get("status"));
        assertEquals("request-1", response.get("requestId"));
        assertEquals(List.of("llm.requests.low"), response.get("kafkaTopicsPurged"));
    }

    @Test
    void resetDoesNotReportSuccessWhenKafkaPurgeFails() {
        ProjectionStore projections = new ProjectionStore();
        AgentRunStore agentRuns = new AgentRunStore();
        projections.put(projection("run-1"));
        agentRuns.putRun(new AgentRun("run-1", "mission-1", FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A, Map.of()));
        TestKafkaTopicProvisioner kafkaTopics = new TestKafkaTopicProvisioner(List.of());
        kafkaTopics.failure = new IllegalStateException("broker unavailable");
        ResetService reset = resetService(projections, agentRuns, kafkaTopics);

        IllegalStateException failure = assertThrows(IllegalStateException.class, reset::reset);

        assertEquals("broker unavailable", failure.getMessage());
        assertEquals(1, projections.all().size());
        assertEquals(1, agentRuns.runs().size());
    }

    private static ResetService resetService(ProjectionStore projections, AgentRunStore agentRuns, KafkaTopicProvisioner kafkaTopics) {
        ResetService reset = new ResetService();
        FrameworkFixtures.set(reset, "projections", projections);
        FrameworkFixtures.set(reset, "agentRuns", agentRuns);
        FrameworkFixtures.set(reset, "kafkaTopics", kafkaTopics);
        return reset;
    }

    private static AgentRunProjection projection(String runId) {
        return new AgentRunProjection(
                runId,
                "mission-1",
                null,
                "running",
                Map.of(),
                Map.of(),
                Map.of(),
                null,
                "low-pass",
                List.of(),
                List.of(),
                Map.of(),
                List.of(),
                Instant.now());
    }

    private static final class TestKafkaTopicProvisioner extends KafkaTopicProvisioner {
        private final List<String> purgedTopics;
        private RuntimeException failure;
        private int purgeCalls;

        private TestKafkaTopicProvisioner(List<String> purgedTopics) {
            this.purgedTopics = purgedTopics;
        }

        @Override
        public List<String> purgeConfiguredTopics() {
            purgeCalls++;
            if (failure != null) {
                throw failure;
            }
            return purgedTopics;
        }
    }
}
