package us.nusun.llmshare.example.tests;

import org.junit.jupiter.api.Test;
import us.nusun.llmshare.example.persona.EthanPersona;
import us.nusun.llmshare.example.persona.MilesPersona;
import us.nusun.llmshare.example.persona.NoahPersona;
import us.nusun.llmshare.example.persona.OwenPersona;
import us.nusun.llmshare.example.routine.ApplicationDeveloperRoutine;
import us.nusun.llmshare.example.routine.DirectorRoutine;
import us.nusun.llmshare.example.routine.ProductOwnerRoutine;
import us.nusun.llmshare.example.routine.ProjectManagerRoutine;
import us.nusun.llmshare.example.runtime.HighPassLane;
import us.nusun.llmshare.example.runtime.LowPassLane;
import us.nusun.llmshare.example.runtime.SynthesisHighRuntime;
import us.nusun.llmshare.example.runtime.SynthesisLowRuntime;
import us.nusun.llmshare.example.runtime.ToolHighRuntime;
import us.nusun.llmshare.example.runtime.ToolLowRuntime;
import us.nusun.llmshare.example.tool.ProjectManagementToolProfile;
import us.nusun.llmshare.example.tool.SourceControlToolProfile;
import us.nusun.llmshare.example.tool.TeamChatToolProfile;
import us.nusun.llmshare.framework.model.ExecutionRuntimeDefinition;
import us.nusun.llmshare.framework.model.InferenceLaneDefinition;
import us.nusun.llmshare.framework.model.Persona;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.model.ToolProfile;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoftwareTeamExampleTest {
    @Test
    void softwareTeamExamplePublishesCoherentUserFacingCatalogs() {
        List<Persona> personas = List.of(
                new NoahPersona().persona(),
                new OwenPersona().persona(),
                new EthanPersona().persona(),
                new MilesPersona().persona());
        List<RoutineDefinition> routines = List.of(
                new DirectorRoutine().routine(),
                new ProjectManagerRoutine().routine(),
                new ProductOwnerRoutine().routine(),
                new ApplicationDeveloperRoutine().routine());
        List<ToolProfile> toolProfiles = List.of(
                new TeamChatToolProfile().toolProfiles().getFirst(),
                new ProjectManagementToolProfile().toolProfiles().getFirst(),
                new SourceControlToolProfile().toolProfiles().getFirst());
        List<InferenceLaneDefinition> lanes = List.of(
                new LowPassLane().inferenceLane(),
                new HighPassLane().inferenceLane());
        List<ExecutionRuntimeDefinition> runtimes = List.of(
                new ToolLowRuntime().executionRuntime(),
                new ToolHighRuntime().executionRuntime(),
                new SynthesisLowRuntime().executionRuntime(),
                new SynthesisHighRuntime().executionRuntime());

        assertEquals(Set.of(
                NoahPersona.ID,
                OwenPersona.ID,
                EthanPersona.ID,
                MilesPersona.ID), ids(personas));
        assertTrue(personas.stream().allMatch(persona -> persona.id().startsWith("software-team/")));
        assertTrue(personas.stream().allMatch(persona -> persona.name() != null && !persona.name().isBlank()));
        assertTrue(personas.stream().allMatch(persona -> !persona.traits().isEmpty()));
        assertNotNull(new MilesPersona().persona().identityFor("gitea"));

        Map<String, String> routeUris = routines.stream().collect(Collectors.toMap(RoutineDefinition::id, RoutineDefinition::routeUri));
        assertEquals(Set.of(
                DirectorRoutine.ID,
                ProjectManagerRoutine.ID,
                ProductOwnerRoutine.ID,
                ApplicationDeveloperRoutine.ID), routeUris.keySet());
        assertTrue(routeUris.values().stream().allMatch(uri -> uri.startsWith("direct:example-software-team-")));

        ToolProfile projectManagement = toolProfile(toolProfiles, ProjectManagementToolProfile.ID);
        assertTrue(projectManagement.allowsTool("plane", "list_projects"));
        assertTrue(projectManagement.allowsTool("plane", "list_work_items"));
        assertFalse(projectManagement.allowsTool("plane", "delete_project"));
        assertEquals("id,name,sequence_id,state,priority", projectManagement.defaultArguments("plane", "list_work_items").get("fields").asText());
        assertTrue(toolProfile(toolProfiles, SourceControlToolProfile.ID).allowsTool("gitea", "get_repository"));
        assertTrue(toolProfile(toolProfiles, TeamChatToolProfile.ID).allowsTool("mattermost", "create_post"));
        assertEquals(Set.of(TeamChatToolProfile.ID, ProjectManagementToolProfile.ID, SourceControlToolProfile.ID),
                toolProfiles.stream().map(ToolProfile::id).collect(Collectors.toSet()));

        assertEquals(Set.of("low-pass", "high-pass"), lanes.stream().map(InferenceLaneDefinition::id).collect(Collectors.toSet()));
        assertEquals(Set.of("tool-low", "tool-high", "synthesis-low", "synthesis-high"), runtimes.stream().map(ExecutionRuntimeDefinition::id).collect(Collectors.toSet()));
        assertTrue(runtimes.stream().filter(ExecutionRuntimeDefinition::toolCalling).allMatch(runtime -> runtime.id().startsWith("tool-")));
    }

    @Test
    void softwareTeamDirectorSchemaIsPackagedForDocumentationAndRuntimeUse() throws Exception {
        try (var input = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("schemas/example/software-team/agent-run-requests-schema.json")) {
            assertNotNull(input);
            String schema = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(schema.contains("agentRuns"));
            assertTrue(schema.contains("personaId"));
            assertTrue(schema.contains("routineId"));
        }
    }

    private static Set<String> ids(List<Persona> personas) {
        return personas.stream().map(Persona::id).collect(Collectors.toSet());
    }

    private static ToolProfile toolProfile(List<ToolProfile> profiles, String id) {
        return profiles.stream()
                .filter(profile -> profile.id().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
