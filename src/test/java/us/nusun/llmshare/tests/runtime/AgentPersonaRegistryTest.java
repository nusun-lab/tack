package us.nusun.llmshare.tests.runtime;

import org.junit.jupiter.api.Test;
import us.nusun.llmshare.team.AgentPersonaDefinition;
import us.nusun.llmshare.team.AgentPersonaRegistry;
import us.nusun.llmshare.team.RoutineStepDefinition;
import us.nusun.llmshare.team.TeamOperatingContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPersonaRegistryTest {
    @Test
    void registryPreservesIdentityMapsAndRoleGuides() {
        AgentPersonaDefinition projectManager = new AgentPersonaDefinition(
                "software-team/project-manager",
                "project-manager",
                "Project Manager",
                "direct:software-team-project-manager",
                "software-team-project-manager",
                "PM",
                "38;2;255;179;71",
                "wake",
                "sleep",
                "Coordinate work.",
                "prompt",
                Map.of("mattermost", "@pm", "gitea", "pm-bot"),
                List.of(new RoutineStepDefinition("step-1", "Do one thing.")),
                true,
                true
        );
        AgentPersonaDefinition observer = new AgentPersonaDefinition(
                "software-team/test-observer",
                "test-observer",
                "Test Observer",
                "direct:software-team-test-observer",
                "software-team-test-observer",
                "TST",
                "38;2;1;2;3",
                "wake",
                "sleep",
                "Observe.",
                "prompt",
                Map.of("mattermost", "@observer"),
                List.of(),
                true,
                false
        );
        AgentPersonaRegistry registry = new AgentPersonaRegistry(
                List.of(projectManager, observer),
                new TeamOperatingContext("software-team", "Software Team", "David Gordon", "@aphelio", "#forum-agents", List.of("Mattermost"), List.of("Backstage"), List.of("Private only"), List.of("One step at a time")));

        assertEquals("@pm", registry.getRequired("software-team/project-manager").platformIdentities().get("mattermost"));
        assertTrue(registry.directorRoleGuide().contains("software-team/project-manager"));
        assertTrue(registry.directorTeamRoster().contains("David Gordon"));
        assertEquals(1, registry.directorAssignablePersonas().size());
    }

    @Test
    void registryRejectsUnknownOrUnassignableRoles() {
        AgentPersonaRegistry registry = new AgentPersonaRegistry(
                List.of(new AgentPersonaDefinition(
                        "software-team/test-observer",
                        "test-observer",
                        "Test Observer",
                        "direct:software-team-test-observer",
                        "software-team-test-observer",
                        "TST",
                        "38;2;1;2;3",
                        "wake",
                        "sleep",
                        "Observe.",
                        "prompt",
                        Map.of(),
                        List.of(),
                        true,
                        false
                )),
                new TeamOperatingContext("software-team", "Software Team", "David Gordon", "@aphelio", "#forum-agents", List.of(), List.of(), List.of(), List.of()));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> registry.validateAssignments(Map.of("software-team/test-observer", "Observe this.")));
        assertEquals("Director assignment role is not assignable: software-team/test-observer", failure.getMessage());
    }
}
