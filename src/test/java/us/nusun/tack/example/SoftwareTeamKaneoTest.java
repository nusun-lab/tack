package us.nusun.tack.example;

import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
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
import us.nusun.tack.example.tool.DeliveryManagementToolProfile;
import us.nusun.tack.example.tool.EngineeringWorkToolProfile;
import us.nusun.tack.example.tool.GiteaMcpServer;
import us.nusun.tack.example.tool.IntegrationToolProfile;
import us.nusun.tack.example.tool.KaneoMcpServer;
import us.nusun.tack.example.tool.MattermostMcpServer;
import us.nusun.tack.example.tool.ProductOwnershipToolProfile;
import us.nusun.tack.example.tool.QualityManagementToolProfile;
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
                whoami list_workspace_members list_tasks get_task get_task_relations list_task_comments create_task
                create_label update_task update_task_status update_task_assignee create_task_relation create_task_comment
                """);
        assertTools(new DeliveryManagementToolProfile(kaneo, gitea), """
                whoami list_workspace_members list_tasks get_task get_task_by_ticket_id get_task_relations
                list_task_comments create_task create_label update_task update_task_status update_task_assignee
                create_task_relation delete_task_relation create_task_comment
                """);
        assertTools(new EngineeringWorkToolProfile(kaneo), """
                whoami list_tasks get_task get_task_by_ticket_id get_task_relations list_task_comments
                update_task_status create_task_comment
                """);
        assertTools(new QualityManagementToolProfile(kaneo), """
                whoami list_workspace_members list_tasks get_task get_task_by_ticket_id get_task_relations
                list_task_comments create_task create_label update_task update_task_status update_task_assignee
                create_task_relation create_task_comment
                """);
        assertTools(new IntegrationToolProfile(kaneo, gitea), """
                list_tasks get_task get_task_by_ticket_id list_task_comments
                """);
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
                    "director-focus-staffing", "director-staffing-read&toolProfile=workspace-readonly",
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
            assertTrue(root.contains("repository locators across synthesis and consolidation"));
            String staffingPurpose = headers(context, "director-focus-staffing", TackHeaders.THOUGHT_PURPOSE);
            assertTrue(staffingPurpose.contains("repository architecture"));
            assertTrue(staffingPurpose.contains("qualifying Epic evidence"));
            String staffing = headers(context, "director-focus-staffing", TackHeaders.PROMPT);
            for (String rule : List.of("Project Manager is always required", "PO and ARCH required",
                    "component repositories", "to-do or in-progress", "attached epic label",
                    "unknown", "capacity remains, agentRuns must include it")) {
                assertTrue(staffing.contains(rule), rule);
            }
            String planning = headers(context, "pm-planning", TackHeaders.PROMPT);
            assertTrue(planning.contains("miles@example.test"));
            assertTrue(planning.contains("avery@example.test"));
            assertFalse(planning.contains("miles-test-token"));
            assertTrue(headers(context, "dev-work-selection", TackHeaders.PROMPT).contains("update_task_status"));
            assertTrue(headers(context, "qa-focus-defect-feedback", TackHeaders.PROMPT).contains("no-priority"));
            assertTrue(headers(context, "po-epic-acceptance", TackHeaders.PROMPT).contains("Archived work does not count as success"));
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
