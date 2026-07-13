package us.nusun.llmshare.framework.tests.unit;

import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.model.Mission;
import us.nusun.llmshare.framework.model.MissionRunCapacity;
import us.nusun.llmshare.framework.service.AgentRunCapacityService;
import us.nusun.llmshare.framework.state.AgentRun;
import us.nusun.llmshare.framework.state.AgentRunStore;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRunCapacityServiceTest {
    @Test
    void activeRunCountIncludesAcceptedScheduledAndRunningOnly() {
        AgentRunStore store = new AgentRunStore();
        store.putMission(new Mission("mission-1", "Build", List.of(), "active", Map.of(), Instant.now()));
        store.putRun(run("accepted", "mission-1", AgentRun.STATUS_ACCEPTED));
        store.putRun(run("scheduled", "mission-1", AgentRun.STATUS_SCHEDULED));
        store.putRun(run("running", "mission-1", AgentRun.STATUS_RUNNING));
        store.putRun(run("complete", "mission-1", AgentRun.STATUS_COMPLETE));
        store.putRun(run("failed", "mission-1", AgentRun.STATUS_FAILED));
        AgentRunCapacityService service = new AgentRunCapacityService(store);

        assertEquals(3, service.activeRunCount("mission-1"));
        assertTrue(AgentRunStore.isActiveStatus(AgentRun.STATUS_ACCEPTED));
        assertTrue(AgentRunStore.isActiveStatus(AgentRun.STATUS_SCHEDULED));
        assertTrue(AgentRunStore.isActiveStatus(AgentRun.STATUS_RUNNING));
        assertFalse(AgentRunStore.isActiveStatus(AgentRun.STATUS_COMPLETE));
        assertFalse(AgentRunStore.isActiveStatus(AgentRun.STATUS_FAILED));
    }

    @Test
    void countsAreIsolatedByMissionAndParentageDoesNotMatter() {
        AgentRunStore store = new AgentRunStore();
        AgentRun parent = run("parent", "mission-1", AgentRun.STATUS_RUNNING);
        AgentRun child = new AgentRun("child", "mission-1", "parent", "worker", "test-routine", Map.of());
        child.status(AgentRun.STATUS_ACCEPTED);
        AgentRun otherMission = run("other", "mission-2", AgentRun.STATUS_RUNNING);
        store.putRun(parent);
        store.putRun(child);
        store.putRun(otherMission);
        AgentRunCapacityService service = new AgentRunCapacityService(store);

        assertEquals(2, service.activeRunCount("mission-1"));
        assertEquals(1, service.activeRunCount("mission-2"));
    }

    @Test
    void capacitySupportsArbitraryMaximums() {
        AgentRunStore store = new AgentRunStore();
        store.putRun(run("one", "mission-1", AgentRun.STATUS_RUNNING));
        store.putRun(run("two", "mission-1", AgentRun.STATUS_ACCEPTED));
        AgentRunCapacityService service = new AgentRunCapacityService(store);

        MissionRunCapacity capacity = service.capacity("mission-1", 5);
        MissionRunCapacity full = service.capacity("mission-1", 2);

        assertEquals(5, capacity.maximum());
        assertEquals(2, capacity.active());
        assertEquals(3, capacity.remaining());
        assertFalse(capacity.full());
        assertEquals(0, full.remaining());
        assertTrue(full.full());
    }

    private static AgentRun run(String id, String missionId, String status) {
        AgentRun run = new AgentRun(id, missionId, "worker", "test-routine", Map.of());
        run.status(status);
        return run;
    }
}
