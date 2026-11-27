package us.nusun.tack.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.model.ProcessorDefinition;
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
import java.util.stream.Stream;

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
        var quality = new QualityManagementToolProfile(kaneo, gitea);
        assertEquals(Set.of("search_repos", "list_pull_requests", "pull_request_read"),
                quality.toolProfiles().getFirst().policies().get("gitea").allowed());
        assertTools(quality, """
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
    void directorStartsWithRosterOnlyAndRetainsFocusedStaffingEvidence() throws Exception {
        try (var context = workflowContext()) {
            var startup = targets(context, "example-software-team-director");
            assertInOrder(startup, "tack:thought-enter?name=director-root",
                    "tack:act?id=director-startup-roster&toolProfile=none", "direct:director-start-missing-runs",
                    "direct:director-orientation");
            assertInOrder(targets(context, "director-focus-staffing"),
                    "tack:thought-enter?name=director-staffing-focus", "tack:act?id=director-staffing-read&toolProfile=workspace-readonly",
                    "direct:director-start-missing-runs", "tack:thought-exit?name=director-staffing-focus");
            assertGuidance(context, "director-start-missing-runs", "Project Manager is always required",
                    "Product Owner and Architect are required", "to-do or in-progress", "required component repository set",
                    "Never duplicate an already-active role", "capacity remains, agentRuns must include it");
            var root = headers(context, "example-software-team-director", TackHeaders.THOUGHT_PURPOSE);
            for (String locator : List.of("workspace/project IDs", "team/channel IDs", "owner/repo pairs", "Epic and PO IDs")) {
                assertTrue(root.contains(locator), locator);
            }
        }
    }

    @Test
    void planningAndActiveDeliveryMaintainIndependentRunwaysAndSeedReadyEpics() throws Exception {
        try (var context = workflowContext()) {
            assertInOrder(targets(context, "pm-focus-planning"), "tack:thought-enter?name=pm-planning-focus",
                    "direct:pm-planning-loop", "direct:pm-awareness", "tack:thought-exit?name=pm-planning-focus");
            assertInOrder(targets(context, "pm-focus-active-flow"), "tack:thought-enter?name=pm-active-flow-focus",
                    "direct:pm-active-flow-loop", "direct:pm-awareness", "tack:thought-exit?name=pm-active-flow-focus");
            assertEquals(List.of("tack:act?id=pm-planning&toolProfile=delivery-management"), targets(context, "pm-planning"));
            assertEquals(List.of("tack:act?id=pm-flow&toolProfile=delivery-management"), targets(context, "pm-flow"));
            assertGuidance(context, "pm-flow", "2–3 executable to-do Stories per engineer", "DEV capacity is separate from ARCH",
                    "in-review and other integration-only waits outside active coding capacity", "all incoming prerequisites done",
                    "first executable Story", "before promoting to-do, even without an AgentRun");
            assertGuidance(context, "pm-planning", "bounded investigation Story", "first executable Story beneath a to-do Epic",
                    "Leave further queue replenishment to active flow", "verify assignment before promoting",
                    "miles@example.test", "avery@example.test");
            assertFalse(headers(context, "pm-planning", TackHeaders.PROMPT).contains("miles-test-token"));
            assertGuidance(context, "pm-bootstrap", "in-review and done", "native virtual buckets");
            assertGuidance(context, "po-epic-definition", "Product-ready to-do needs no repository setup",
                    "deeper to-do queue", "normally 2–3 in-progress Epics",
                    "first Story is to-do, in-progress, in-review, or done");
            assertGuidance(context, "po-epic-acceptance", "QA evidence demonstrates its Gherkin",
                    "Story and Test children are done", "Archived work does not count as success");
        }
    }

    @Test
    void engineeringKeepsDistinctWorkScopesAndGiteaAuthority() throws Exception {
        try (var context = workflowContext()) {
            assertInOrder(targets(context, "dev-work-selection"), "tack:thought-enter?name=dev-selection",
                    "tack:act?id=dev-work-selection&toolProfile=engineering-work", "tack:decide?id=developer-has-work",
                    "tack:thought-exit?name=dev-selection");
            assertInOrder(targets(context, "dev-implementation-loop"), "tack:thought-enter?name=dev-implementation",
                    "tack:act?id=dev-task-context-read", "tack:decide?id=developer-has-valid-task-context",
                    "tack:decide?id=developer-is-topology-investigation", "tack:act?id=dev-topology-investigation",
                    "tack:act?id=dev-topology-finding-update", "tack:act?id=dev-source-context-plan",
                    "tack:act?id=dev-delivery-step", "tack:act?id=dev-validation-step",
                    "tack:act?id=dev-task-evidence-update", "tack:thought-exit?name=dev-implementation");
            assertGuidance(context, "dev-work-selection", "Defect before a Story", "Skip in-review", "independent assigned work",
                    "actionable under a to-do Epic", "incoming prerequisites are done");
            assertGuidance(context, "architect-implementation", "Defects before Stories", "Skip in-review",
                    "start under a to-do Epic", "done incoming prerequisites");
            assertGuidance(context, "dev-implementation-loop", "exact owner/repo/PR, head and validation",
                    "in-review when fresh Gitea evidence shows an open implementation PR with credible validation");
            assertGuidance(context, "architect-implementation", "in-review when an open implementation PR has credible validation");
            for (String route : List.of("dev-focus-reconcile", "architect-focus-reconcile")) {
                assertGuidance(context, route, "in-review", "feedback requiring implementation", "intended base",
                        "Story to done only with required validation", "Defect", "in-progress for QA retest");
            }
            assertInOrder(targets(context, "architect-integration"), "tack:act?id=architect-integration-batch&toolProfile=integration",
                    "tack:decide?id=architect-needs-conflict-recovery", "tack:act?id=architect-conflict-recovery");
            assertGuidance(context, "architect-integration", "QA test-code PRs", "Do not require a Kaneo in-review transition");
            assertGuidance(context, "architect-repository-architecture", "gitops/README.md", "docs/architecture.md",
                    "tests/system/README.md", "<anchor>-service", "<anchor>-api", "API specification/contract");
        }
    }

    @Test
    void qaSeparatesAuthoringIntegrationExecutionAndDefectClosure() throws Exception {
        try (var context = workflowContext()) {
            assertInOrder(targets(context, "qa-test-engineering"), "tack:act?id=qa-test-work-select",
                    "tack:decide?id=qa-has-test-work", "tack:decide?id=qa-test-next-step&choices=author,execute",
                    "tack:act?id=qa-test-evidence-update");
            assertGuidance(context, "qa-test-engineering", "author tests before implementation is available",
                    "test-code PR awaiting integration moves it to in-review", "actual required behavioral evidence");
            assertGuidance(context, "qa-focus-test-review", "After verified integration, resume in-progress",
                    "required automation is integrated and actual required behavioral evidence");
            assertGuidance(context, "qa-test-execute", "current integrated source", "Local sandbox execution is evidence");
            assertGuidance(context, "qa-focus-test-planning", "2–3 active Test units", "2–3 executable to-do",
                    "In-review or deployment-only waits do not consume active execution capacity");
            assertInOrder(targets(context, "qa-defect-retest"), "tack:act?id=qa-defect-retest-context",
                    "tack:act?id=qa-defect-retest-execution", "tack:decide?id=qa-defect-fixed",
                    "tack:act?id=qa-defect-retest-update");
            assertGuidance(context, "qa-defect-retest", "whose fix is verified merged", "close the Defect as done",
                    "keep the Defect in-progress", "in-review when its required QA PR is open");
            assertGuidance(context, "qa-focus-defect-feedback", "no-priority", "repair assignment unset until PO triage");
            assertGuidance(context, "qa-epic-evidence", "behavior evidence", "PO owns the acceptance decision and Epic status");
        }
    }

    private DefaultCamelContext workflowContext() throws Exception {
        var accounts = kaneoConfig().getConfigMapping(PersonaAccounts.class);
        var context = new DefaultCamelContext();
        for (RouteBuilder routine : List.of(new DirectorRoutine(), new ProjectManagerRoutine(accounts),
                new ProductOwnerRoutine(), new ArchitectRoutine(), new ApplicationDeveloperRoutine(),
                new QualityEngineerRoutine())) {
            context.addRoutes(routine);
        }
        return context;
    }

    private static List<String> targets(DefaultCamelContext context, String route) {
        return descendants(context.getRouteDefinition(route)).filter(ToDefinition.class::isInstance)
                .map(ToDefinition.class::cast).map(ToDefinition::getUri).toList();
    }

    private static Stream<ProcessorDefinition<?>> descendants(ProcessorDefinition<?> element) {
        return element.getOutputs().stream().flatMap(child -> Stream.concat(Stream.of(child), descendants(child)));
    }

    private static void assertInOrder(List<String> actual, String... expected) {
        var remaining = actual.iterator();
        for (String step : expected) {
            boolean found = false;
            while (remaining.hasNext() && !found) {
                found = remaining.next().startsWith(step);
            }
            assertTrue(found, () -> "Missing or out of order: " + step + " in " + actual);
        }
    }

    private static void assertGuidance(DefaultCamelContext context, String route, String... phrases) {
        var prompt = headers(context, route, TackHeaders.PROMPT).replaceAll("\\s+", " ");
        for (String phrase : phrases) {
            assertTrue(prompt.contains(phrase), route + ": " + phrase);
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
        return String.join("\n", descendants(context.getRouteDefinition(route))
                .filter(SetHeaderDefinition.class::isInstance).map(SetHeaderDefinition.class::cast)
                .filter(step -> name.equals(step.getName()))
                .map(step -> step.getExpression().getExpression()).toList());
    }
}
