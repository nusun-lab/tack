package us.nusun.tack.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.model.LoopDefinition;
import org.apache.camel.model.SetHeaderDefinition;
import org.apache.camel.model.ToDefinition;
import org.junit.jupiter.api.Test;
import us.nusun.tack.example.persona.AveryPersona;
import us.nusun.tack.example.persona.EthanPersona;
import us.nusun.tack.example.persona.MilesPersona;
import us.nusun.tack.example.persona.NoahPersona;
import us.nusun.tack.example.persona.OwenPersona;
import us.nusun.tack.example.persona.QuinnPersona;
import us.nusun.tack.example.routine.ApplicationDeveloperRoutine;
import us.nusun.tack.example.routine.ArchitectRoutine;
import us.nusun.tack.example.routine.DirectorRoutine;
import us.nusun.tack.example.routine.ProductOwnerRoutine;
import us.nusun.tack.example.routine.ProjectManagerRoutine;
import us.nusun.tack.example.routine.QualityEngineerRoutine;
import us.nusun.tack.example.tool.ArchitectSourceControlWriteToolProfile;
import us.nusun.tack.example.tool.DeliveryManagementToolProfile;
import us.nusun.tack.example.tool.EngineeringWorkToolProfile;
import us.nusun.tack.example.tool.GiteaMcpServer;
import us.nusun.tack.example.tool.IntegrationToolProfile;
import us.nusun.tack.example.tool.KaneoMcpServer;
import us.nusun.tack.example.tool.MattermostMcpServer;
import us.nusun.tack.example.tool.ProductOwnershipToolProfile;
import us.nusun.tack.example.tool.QualityManagementToolProfile;
import us.nusun.tack.example.tool.SourceControlToolProfile;
import us.nusun.tack.example.tool.TeamChatToolProfile;
import us.nusun.tack.example.tool.WorkspaceCoordinationToolProfile;
import us.nusun.tack.example.tool.WorkspaceReadonlyToolProfile;
import us.nusun.tack.framework.persona.PersonaAccounts;
import us.nusun.tack.framework.routine.TackHeaders;
import us.nusun.tack.framework.tool.ToolProfile;
import us.nusun.tack.framework.tool.ToolProfileProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoftwareTeamKaneoTest {
    private static final String URL = "https://kaneo.example.test/api/mcp";

    @Test
    void configBindsOneNativeConnectionAndSixDistinctPersonaIdentities() throws Exception {
        var config = kaneoConfig();
        var connection = new KaneoMcpServer(config.getValue("agent.tool.mcp.kaneo.url", String.class)).definition();
        assertEquals("kaneo", connection.id());
        assertEquals(URL, connection.url());
        assertEquals("streamableHttp", connection.transportType());
        assertEquals("Authorization", connection.authHeaderName());
        assertEquals("Bearer %s", connection.authHeaderValueFormat());
        assertTrue(connection.extraHeaders().isEmpty());

        var accounts = config.getConfigMapping(PersonaAccounts.class);
        var personas = List.of(new NoahPersona(accounts).persona(), new OwenPersona(accounts).persona(),
                new EthanPersona(accounts).persona(), new AveryPersona(accounts).persona(),
                new MilesPersona(accounts).persona(), new QuinnPersona(accounts).persona());
        for (var persona : personas) {
            String name = persona.name().toLowerCase(Locale.ROOT);
            assertEquals(Set.of("kaneo", "gitea", "mattermost"), persona.identities().keySet());
            var identity = persona.identityFor("kaneo");
            assertEquals(name + "-test-token", identity.token());
            assertEquals(name + "@example.test", identity.handle());
        }
    }

    @Test
    void exposesExactNativeToolsByResponsibility() {
        var kaneo = new KaneoMcpServer(URL);
        var gitea = new GiteaMcpServer("");
        var chat = new MattermostMcpServer("");
        assertTools(new WorkspaceReadonlyToolProfile(kaneo, gitea, chat), """
                whoami list_workspaces list_workspace_members list_projects get_project list_project_columns
                list_tasks get_task get_task_by_ticket_id get_task_relations list_task_comments list_task_activity
                """);
        assertTools(new WorkspaceCoordinationToolProfile(kaneo, chat), """
                whoami list_workspaces list_workspace_members list_projects get_project create_project update_project
                list_project_columns
                """);
        assertTools(new ProductOwnershipToolProfile(kaneo), """
                whoami list_workspaces list_projects list_workspace_members list_tasks get_task get_task_relations list_task_comments create_task
                create_label update_task update_task_status update_task_assignee create_task_relation create_task_comment
                """);
        assertTools(new DeliveryManagementToolProfile(kaneo, gitea), """
                whoami list_workspaces list_projects list_workspace_members list_tasks get_task get_task_by_ticket_id get_task_relations
                list_task_comments create_task create_label update_task update_task_status update_task_assignee
                create_task_relation delete_task_relation create_task_comment
                """);
        var engineering = new EngineeringWorkToolProfile(kaneo, gitea);
        assertTools(engineering, """
                whoami list_workspaces list_projects list_tasks get_task get_task_by_ticket_id get_task_relations list_task_comments
                update_task_status create_task_comment
                """);
        assertEquals(Set.of("search_repos", "list_pull_requests", "pull_request_read"),
                engineering.toolProfiles().getFirst().policies().get("gitea").allowed());
        assertTools(new QualityManagementToolProfile(kaneo), """
                whoami list_workspaces list_projects list_workspace_members list_tasks get_task get_task_by_ticket_id get_task_relations
                list_task_comments create_task create_label update_task update_task_status update_task_assignee
                create_task_relation create_task_comment
                """);
        assertTools(new IntegrationToolProfile(kaneo, gitea), """
                whoami list_workspaces list_projects list_tasks get_task get_task_by_ticket_id list_task_comments
                """);
    }

    @Test
    void sourceAndChannelProfilesOfferTargetedDiscovery() {
        var gitea = new GiteaMcpServer("");
        var chat = new MattermostMcpServer("");
        for (var provider : List.of(new SourceControlToolProfile(gitea),
                new ArchitectSourceControlWriteToolProfile(gitea))) {
            var profile = provider.toolProfiles().getFirst();
            assertTrue(profile.allowsTool("gitea", "search_repos"));
            assertFalse(profile.allowsTool("gitea", "list_org_repos"));
            assertFalse(profile.allowsTool("gitea", "list_my_repos"));
        }
        for (var provider : List.of(new TeamChatToolProfile(chat), new WorkspaceCoordinationToolProfile(new KaneoMcpServer(URL), chat),
                new WorkspaceReadonlyToolProfile(new KaneoMcpServer(URL), gitea, chat))) {
            var profile = provider.toolProfiles().getFirst();
            assertTrue(profile.allowsTool("mattermost", "list_teams"));
            assertTrue(profile.allowsTool("mattermost", "get_channel_by_name"));
            assertFalse(profile.allowsTool("mattermost", "list_my_channels"));
        }
    }

    @Test
    void routinesBindNativeProfilesAndPreserveDirectorStaffingEvidence() throws Exception {
        var accounts = kaneoConfig().getConfigMapping(PersonaAccounts.class);
        try (var context = new DefaultCamelContext()) {
            for (RouteBuilder routine : List.of(new DirectorRoutine(), new ProjectManagerRoutine(accounts),
                    new ProductOwnerRoutine(), new ArchitectRoutine(), new ApplicationDeveloperRoutine(),
                    new QualityEngineerRoutine())) {
                context.addRoutes(routine);
            }
            var routing = Map.of(
                    "director-evidence", "director-staffing-read&toolProfile=workspace-readonly",
                    "pm-bootstrap", "pm-bootstrap&toolProfile=workspace-coordination",
                    "pm-focus-onboarding", "pm-onboarding&toolProfile=workspace-coordination",
                    "pm-planning", "pm-planning&toolProfile=delivery-management",
                    "po-epic-definition", "po-epic-work&toolProfile=product-ownership",
                    "architect-implementation", "architect-work-select&toolProfile=engineering-work",
                    "architect-integration", "architect-integration-batch&toolProfile=integration",
                    "dev-work-selection", "dev-work-selection&toolProfile=engineering-work",
                    "qa-focus-defect-feedback", "qa-defect-record&toolProfile=quality-management",
                    "qa-defect-retest", "qa-defect-retest-context&toolProfile=workspace-readonly");
            routing.forEach((route, endpoint) -> {
                var definition = context.getRouteDefinition(route);
                assertNotNull(definition, route);
                assertTrue(definition.getOutputs().stream()
                        .filter(ToDefinition.class::isInstance).map(ToDefinition.class::cast)
                        .anyMatch(step -> ("tack:act?id=" + endpoint).equals(step.getUri())), route);
            });

            String root = headers(context, "example-software-team-director", TackHeaders.THOUGHT_PURPOSE);
            assertTrue(root.contains("Preserve verified readiness and exact"));
            assertTrue(root.contains("across synthesis and consolidation"));
            assertTrue(root.contains("qualifying Epic and PO IDs"));
            assertTrue(headers(context, "example-software-team-director", TackHeaders.PROMPT)
                    .contains("PM is always required"));
            assertTrue(context.getRouteDefinition("example-software-team-director").getOutputs().stream()
                    .filter(ToDefinition.class::isInstance).map(ToDefinition.class::cast)
                    .anyMatch(step -> step.getUri().equals("tack:act?id=director-startup-roster&toolProfile=none")));
            assertTrue(context.getRouteDefinition("example-software-team-project-manager").getOutputs().stream()
                    .filter(ToDefinition.class::isInstance).map(ToDefinition.class::cast)
                    .anyMatch(step -> step.getUri().equals("direct:pm-bootstrap")));
            assertTrue(context.getRouteDefinition("example-software-team-project-manager").getOutputs().stream()
                    .filter(LoopDefinition.class::isInstance).map(LoopDefinition.class::cast)
                    .flatMap(loop -> loop.getOutputs().stream())
                    .filter(ToDefinition.class::isInstance).map(ToDefinition.class::cast)
                    .anyMatch(step -> step.getUri().equals("direct:pm-planning")));
            String domainEvidence = headers(context, "director-evidence", TackHeaders.PROMPT);
            assertTrue(domainEvidence.contains("Report eligible vacancies and unknown gates"));
            String staffing = (domainEvidence + headers(context, "director-focus-staffing", TackHeaders.PROMPT))
                    .replaceAll("\\s+", " ");
            for (String rule : List.of("Project Manager is always required", "Product Owner and Architect are required",
                    "component repository", "to-do or in-progress", "attached epic label", "Start all eligible roles in the same batch",
                    "unknown", "capacity remains, agentRuns must include it")) {
                assertTrue(staffing.contains(rule), rule);
            }
            String planning = headers(context, "pm-planning", TackHeaders.PROMPT);
            assertTrue(planning.contains("miles@example.test"));
            assertTrue(planning.contains("avery@example.test"));
            assertFalse(planning.contains("miles-test-token"));
            assertTrue(planning.contains("A to-do Epic is sufficient for its first executable Story"));
            assertTrue(planning.contains("repair an absent or non-engineering assignee"));
            assertTrue(planning.contains("that engineer's userId"));
            assertTrue(planning.indexOf("that engineer's userId") < planning.indexOf("the assigned Story to to-do"));
            String epic = headers(context, "po-epic-definition", TackHeaders.PROMPT);
            assertTrue(epic.contains("as soon as it is product-ready"));
            assertTrue(epic.contains("independently of repositories, Stories, staffing and chat"));
            assertTrue(epic.contains("once its first assigned Story is to-do, in-progress or done"));
            assertTrue(epic.contains("In this act, write only Epics"));
            assertTrue(headers(context, "pm-focus-onboarding", TackHeaders.PROMPT)
                    .contains("verified missing active teammates"));
            String architecture = headers(context, "architect-repository-architecture", TackHeaders.PROMPT);
            assertTrue(architecture.contains("Create only verified missing repositories with gitea.create_repo"));
            assertTrue(architecture.contains("Use a placeholder entry point and a build"));
            for (String route : List.of("dev-work-selection", "architect-implementation")) {
                assertTrue(headers(context, route, TackHeaders.PROMPT)
                        .contains("parent Epic may be to-do or in-progress"), route);
            }
            assertTrue(headers(context, "dev-work-selection", TackHeaders.PROMPT).contains("update_task_status"));
            assertTrue(headers(context, "dev-implementation-step", TackHeaders.PROMPT).contains("test locally"));
            assertTrue(headers(context, "qa-focus-defect-feedback", TackHeaders.PROMPT).contains("no-priority"));
            assertTrue(headers(context, "po-epic-acceptance", TackHeaders.PROMPT).contains("Archived work does not count as success"));
        }
    }

    @Test
    void directorStaffingSelectsWholeMissionRoutinesWithoutDelegationInstructions() throws Exception {
        try (var schema = getClass().getResourceAsStream("/schemas/example/software-team/agent-run-requests-schema.json")) {
            assertNotNull(schema);
            var run = new ObjectMapper().readTree(schema).at("/properties/agentRuns/items");
            var properties = run.path("properties");
            assertEquals(2, properties.size());
            assertTrue(properties.has("personaId"));
            assertTrue(properties.has("routineId"));
            assertFalse(run.path("additionalProperties").asBoolean(true));
        }
    }

    private SmallRyeConfig kaneoConfig() throws Exception {
        var env = new HashMap<String, String>();
        env.put("KANEO_MCP_URL", URL);
        for (String name : List.of("noah", "owen", "ethan", "avery", "miles", "quinn")) {
            String prefix = name.toUpperCase(Locale.ROOT);
            env.put(prefix + "_KANEO_TOKEN", name + "-test-token");
            env.put("PERSONA_" + prefix + "_EMAIL", name + "@example.test");
        }
        return new SmallRyeConfigBuilder().addDefaultInterceptors()
                .withSources(new PropertiesConfigSource(getClass().getResource("/application.properties")),
                        new EnvConfigSource(env, 300))
                .withMapping(PersonaAccounts.class)
                .withMappingIgnore("**")
                .build();
    }

    private static void assertTools(ToolProfileProvider provider, String tools) {
        ToolProfile profile = provider.toolProfiles().getFirst();
        var policy = profile.policies().get("kaneo");
        assertNotNull(policy);
        assertEquals(Set.of(tools.strip().split("\\s+")), policy.allowed(), profile.id());
        assertFalse(policy.allowAll(), profile.id());
        assertFalse(profile.allowsTool("kaneo", "delete_task"), profile.id());
    }

    private static String headers(DefaultCamelContext context, String route, String name) {
        return String.join("\n", context.getRouteDefinition(route).getOutputs().stream()
                .filter(SetHeaderDefinition.class::isInstance).map(SetHeaderDefinition.class::cast)
                .filter(step -> name.equals(step.getName()))
                .map(step -> step.getExpression().getExpression()).toList());
    }
}
