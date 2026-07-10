package us.nusun.llmshare.framework.tests.boundary;

import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.model.AgentRunProjection;
import us.nusun.llmshare.framework.projection.AgentRunProjectionManager;
import us.nusun.llmshare.framework.projection.ProjectionStore;
import us.nusun.llmshare.framework.state.AgentRun;
import us.nusun.llmshare.framework.workspace.WorkspaceViewService;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WorkspaceViewServiceBoundaryTest {
    @Test
    void viewIncludesStaticCatalogsAndEmptyProjectionListWhenNoRunsExist() {
        WorkspaceViewService service = service(FrameworkFixtures.projectionStore());

        Map<String, Object> view = service.view();

        assertTrue(assertInstanceOf(List.class, view.get("personas")).size() >= 2);
        assertTrue(assertInstanceOf(List.class, view.get("routines")).size() >= 2);
        assertFalse(view.containsKey("execution" + "Runtimes"));
        assertTrue(assertInstanceOf(List.class, view.get("inferenceLanes")).size() >= 2);
        assertTrue(assertInstanceOf(List.class, view.get("toolProfiles")).size() >= 3);
        assertEquals(List.of(), view.get("agentRunProjections"));
    }

    @Test
    void viewReturnsMultipleAndChildAgentRunProjectionsWithThoughtStacks() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        WorkspaceViewService service = service(store);

        var parentExchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        AgentRun parent = new AgentRun("parent-run", "mission-1", FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A, Map.of());
        parent.currentPrimitive("act");
        parent.currentId("review");
        parentExchange.setProperty("agentRun", parent);
        manager.record(parentExchange, "PARENT_STARTED");

        var childExchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        AgentRun child = new AgentRun("child-run", "mission-1", "parent-run", FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A, Map.of());
        child.currentPrimitive("act");
        child.currentId("implement");
        childExchange.setProperty("agentRun", child);
        new us.nusun.llmshare.framework.routine.ThoughtStackSupport().push("Child implementation context", 200).process(childExchange);
        manager.record(childExchange, "CHILD_STARTED");

        List<?> projections = assertInstanceOf(List.class, service.view().get("agentRunProjections"));
        AgentRunProjection childProjection = projections.stream()
                .map(AgentRunProjection.class::cast)
                .filter(item -> item.agentRunId().equals("child-run"))
                .findFirst()
                .orElseThrow();

        assertEquals(2, projections.size());
        assertEquals("parent-run", childProjection.parentAgentRunId());
        assertEquals("act", childProjection.current().get("primitive"));
        assertEquals("Child implementation context", childProjection.thoughtStack().getFirst().get("context"));
        assertEquals("(unnamed)", childProjection.thoughtStack().getFirst().get("name"));
        assertEquals("(unnamed)", childProjection.thoughtStack().getFirst().get("displayId"));
        assertTrue(childProjection.thoughtStack().getFirst().containsKey("updatedAt"));
    }

    private static WorkspaceViewService service(ProjectionStore store) {
        WorkspaceViewService service = new WorkspaceViewService();
        FrameworkFixtures.set(service, "personas", FrameworkFixtures.personaCatalog());
        FrameworkFixtures.set(service, "routines", FrameworkFixtures.routineCatalog());
        FrameworkFixtures.set(service, "lanes", FrameworkFixtures.inferenceLaneRegistry());
        FrameworkFixtures.set(service, "toolProfiles", FrameworkFixtures.toolProfileRegistry());
        FrameworkFixtures.set(service, "projections", store);
        return service;
    }
}
