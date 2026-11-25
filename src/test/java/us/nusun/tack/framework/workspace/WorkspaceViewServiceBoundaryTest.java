package us.nusun.tack.framework.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import us.nusun.tack.framework.persona.PersonaCatalog;
import us.nusun.tack.framework.tool.ToolProfileRegistry;
import us.nusun.tack.framework.tool.McpServerDefinition;
import us.nusun.tack.framework.persona.Persona;
import us.nusun.tack.framework.tool.ToolProfile;
import us.nusun.tack.framework.run.AgentRunProjection;
import us.nusun.tack.framework.run.AgentRunProjectionManager;
import us.nusun.tack.framework.run.ProjectionStore;
import us.nusun.tack.framework.run.AgentRun;
import us.nusun.tack.framework.tests.support.FrameworkFixtures;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WorkspaceViewServiceBoundaryTest {
    @Test
    void viewIncludesStaticCatalogsAndEmptyProjectionListWhenNoRunsExist() {
        WorkspaceViewService service = service(FrameworkFixtures.projectionStore());

        var view = service.view();

        assertTrue(view.personas().size() >= 2);
        assertTrue(view.routines().size() >= 2);
        assertTrue(view.inferenceLanes().size() >= 2);
        assertTrue(view.toolProfiles().size() >= 3);
        assertEquals(List.of(), view.agentRunProjections());
    }

    @Test
    void completeWorkspaceResponseExposesPolicyAndExcludesCredentials() throws Exception {
        var persona = Persona.builder("developer").name("Developer").color("blue")
                .identity("mcp", "SENTINEL_PERSONA_TOKEN", "@developer").build();
        var profile = ToolProfile.builder("profile").mcpServer(McpServerDefinition.builder("mcp")
                .authHeaderValueFormat("SENTINEL_AUTH_FORMAT %s")
                .extraHeader("X-Private", "SENTINEL_EXTRA_HEADER").build())
                .allowTool("mcp", "workitem")
                .permitArgumentValues("mcp", "workitem", "action", "list", "retrieve")
                .defaultArgument("mcp", "workitem", "action", "list")
                .hideArgument("mcp", "workitem", "pql")
                .build();
        var service = new WorkspaceViewService(new PersonaCatalog(List.of(persona)), FrameworkFixtures.routineCatalog(),
                FrameworkFixtures.inferenceLaneRegistry(), new ToolProfileRegistry(List.of(profile)),
                FrameworkFixtures.projectionStore());

        var mapper = new ObjectMapper().findAndRegisterModules();
        String serialized = mapper.writeValueAsString(service.view());
        var publicProfile = mapper.readTree(serialized).path("toolProfiles").get(0);

        var arguments = publicProfile.at("/servers/mcp/arguments/workitem");
        assertEquals(mapper.valueToTree(List.of("list", "retrieve")), arguments.at("/permittedValues/action"));
        assertEquals("list", arguments.at("/defaults/action").asText());
        assertEquals("pql", arguments.at("/hidden/0").asText());
        assertEquals("X-Private", publicProfile.at("/servers/mcp/connection/extraHeaderNames/0").asText());
        assertFalse(publicProfile.has("mcpServers"));
        assertFalse(serialized.contains("SENTINEL_"));
        assertTrue(serialized.contains("@developer"));
        assertTrue(serialized.contains("blue"));
        assertTrue(serialized.contains("X-Private"));
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
        FrameworkFixtures.primitiveExecutor(store).enterThought(childExchange,
                new us.nusun.tack.framework.routine.PrimitiveExecutor.ThoughtEnterRequest(null, "Child implementation context", 200));
        manager.record(childExchange, "CHILD_STARTED");

        List<AgentRunProjection> projections = service.view().agentRunProjections();
        AgentRunProjection childProjection = projections.stream()
                .filter(item -> item.agentRunId().equals("child-run"))
                .findFirst()
                .orElseThrow();

        assertEquals(2, projections.size());
        assertEquals("parent-run", childProjection.parentAgentRunId());
        assertTrue(childProjection.current().isEmpty());
        assertEquals("Child implementation context", childProjection.thoughtStack().getFirst().context());
        assertEquals("(unnamed)", childProjection.thoughtStack().getFirst().name());
        var json = new ObjectMapper().findAndRegisterModules().valueToTree(childProjection);
        var thought = json.at("/thoughtStack/0");
        assertTrue(thought.has("updatedAt"));
        assertFalse(thought.has("displayId"));
        assertFalse(thought.has("contentPreview"));
        assertFalse(thought.has("truncated"));
    }

    private static WorkspaceViewService service(ProjectionStore store) {
        return new WorkspaceViewService(FrameworkFixtures.personaCatalog(), FrameworkFixtures.routineCatalog(),
                FrameworkFixtures.inferenceLaneRegistry(), FrameworkFixtures.toolProfileRegistry(), store);
    }
}
