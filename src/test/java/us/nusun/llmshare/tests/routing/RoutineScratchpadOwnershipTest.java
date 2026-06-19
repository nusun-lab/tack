package us.nusun.llmshare.tests.routing;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
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
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class RoutineScratchpadOwnershipTest {
    @Inject
    CamelContext camelContext;

    @Inject
    ProducerTemplate producerTemplate;

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

    @Test
    void sameRoleScratchpadKeepsLatestThoughtAndPriorContext() {
        writeScratchpad("software-team/project-manager", "first summary");
        writeScratchpad("software-team/project-manager", "second summary");

        String scratchpad = projectMemoryStore.snapshotScratchpads().get("req-1::software-team/project-manager");

        assertEquals(
                "mock-step: second summary Next: next\n\nmock-step: first summary Next: next",
                scratchpad);
    }

    @Test
    void projectBasicsAreOwnedByProjectManagerSetupSteps() {
        writeScratchpad("software-team/product-owner", "PO summary");
        assertNull(projectMemoryStore.snapshotProjectBasics().get("req-1"));

        Exchange exchange = new DefaultExchange(camelContext);
        exchange.setProperty("requestId", "req-1");
        exchange.setProperty("routineOriginalAgent", "software-team/project-manager");
        exchange.setProperty("currentStep", "chat-channel-setup");
        exchange.setProperty("projectBasics", "chat=#proj | fallback #forum-agents");
        exchange.getIn().setHeader("agent", "software-team/routine-formatter");
        exchange.getIn().setBody(new RoutineStepResult(
                "software-team/routine-formatter",
                "chat-channel-setup",
                "Mock routine",
                "PM setup summary",
                "next",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false,
                false
        ));

        routineStepSupport.applyRoutineStepResult(exchange);
        producerTemplate.send("direct:workspace-project-basics-store", exchange);

        assertEquals("chat=#proj | fallback #forum-agents", projectMemoryStore.snapshotProjectBasics().get("req-1"));
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
        producerTemplate.send("direct:workspace-scratchpad-store", exchange);
    }
}
