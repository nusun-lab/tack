package us.nusun.llmshare;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.camel.support.DefaultExchange;
import us.nusun.llmshare.bean.ProjectMemoryStore;
import us.nusun.llmshare.bean.RoutineStepSupport;
import us.nusun.llmshare.model.RoutineStepResult;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@QuarkusTest
class RoutineScratchpadOwnershipTest {
    @Inject
    CamelContext camelContext;

    @Inject
    RoutineStepSupport routineStepSupport;

    @Inject
    ProjectMemoryStore projectMemoryStore;

    @BeforeEach
    void clearStore() {
        projectMemoryStore.clear();
    }

    @Test
    void sameRequestProducesDistinctScratchpadsPerPrimaryAgent() {
        writeScratchpad("software-team/project-manager", "PM summary");
        writeScratchpad("software-team/product-owner", "PO summary");
        writeScratchpad("software-team/application-developer", "DEV summary");

        Map<String, String> scratchpads = projectMemoryStore.snapshotScratchpads().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("req-1::"))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertEquals(3, scratchpads.size());
        assertEquals("mock-step: PM summary Next: next", scratchpads.get("req-1::software-team/project-manager"));
        assertEquals("mock-step: PO summary Next: next", scratchpads.get("req-1::software-team/product-owner"));
        assertEquals("mock-step: DEV summary Next: next", scratchpads.get("req-1::software-team/application-developer"));
    }

    @Test
    void formatterRoleIsNotUsedAsScratchpadOwnerWhenOriginalAgentExists() {
        writeScratchpad("software-team/project-manager", "PM summary");

        Map<String, String> scratchpads = projectMemoryStore.snapshotScratchpads().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("req-1::"))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertFalse(scratchpads.containsKey("req-1::software-team/routine-formatter"));
    }

    private void writeScratchpad(String originalAgentRoleId, String summary) {
        Exchange exchange = new DefaultExchange(camelContext);
        exchange.setProperty("requestId", "req-1");
        exchange.setProperty("routineOriginalAgent", originalAgentRoleId);
        exchange.getIn().setHeader("agent", "software-team/routine-formatter");
        exchange.getIn().setBody(new RoutineStepResult(
                "software-team/routine-formatter",
                "mock-step",
                "Mock routine",
                summary,
                "next",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false,
                false
        ));
        routineStepSupport.applyRoutineStepResult(exchange);
    }
}
