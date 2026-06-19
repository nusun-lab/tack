package us.nusun.llmshare.tests.runtime;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.bean.ProjectMemoryStore;
import us.nusun.llmshare.model.DirectorStateRecord;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class ProjectMemoryStoreTest {
    @Inject
    ProjectMemoryStore projectMemoryStore;

    @BeforeEach
    void clear() {
        projectMemoryStore.clear();
    }

    @Test
    void canReadAndRemoveDirectorStateByRequestId() {
        projectMemoryStore.applyDirectorState(new DirectorStateRecord(
                "req-existing",
                "Build a simple parking lot manager app.",
                Map.of("software-team/project-manager", "Coordinate delivery."),
                "2026-06-19T00:00:00Z"));

        DirectorStateRecord found = projectMemoryStore.getDirectorState("req-existing");

        assertEquals("req-existing", found.requestId());

        projectMemoryStore.removeDirectorState("req-existing");

        assertNull(projectMemoryStore.getDirectorState("req-existing"));
    }
}
