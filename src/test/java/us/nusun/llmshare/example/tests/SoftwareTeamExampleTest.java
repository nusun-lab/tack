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
import us.nusun.llmshare.example.tool.GiteaMcpServer;
import us.nusun.llmshare.example.tool.MattermostMcpServer;
import us.nusun.llmshare.example.tool.PlaneMcpServer;
import us.nusun.llmshare.example.tool.ProjectManagementToolProfile;
import us.nusun.llmshare.example.tool.SourceControlToolProfile;
import us.nusun.llmshare.example.tool.TeamChatToolProfile;
import us.nusun.llmshare.example.tool.WorkspaceCoordinationToolProfile;
import us.nusun.llmshare.example.tool.WorkspaceReadonlyToolProfile;
import us.nusun.llmshare.framework.model.InferenceLaneDefinition;
import us.nusun.llmshare.framework.model.McpServerDefinition;
import us.nusun.llmshare.framework.model.Persona;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.model.ToolProfile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoftwareTeamExampleTest {
    private static final Set<String> PROFILE_IDS = Set.of(
            WorkspaceReadonlyToolProfile.ID,
            WorkspaceCoordinationToolProfile.ID,
            ProjectManagementToolProfile.ID,
            SourceControlToolProfile.ID,
            TeamChatToolProfile.ID);
    private static final Set<String> SERVER_IDS = Set.of("plane", "gitea", "mattermost");

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
        List<ToolProfile> toolProfiles = toolProfiles();
        List<InferenceLaneDefinition> lanes = List.of(
                new LowPassLane().inferenceLane(),
                new HighPassLane().inferenceLane());

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
        assertTrue(routines.stream().allMatch(routine -> "high-pass".equals(routine.defaultLane())));

        assertEquals(PROFILE_IDS, toolProfiles.stream().map(ToolProfile::id).collect(Collectors.toSet()));
        assertEquals(5, toolProfiles.size());

        ToolProfile projectManagement = toolProfile(toolProfiles, ProjectManagementToolProfile.ID);
        assertTrue(projectManagement.allowsTool("plane", "list_projects"));
        assertTrue(projectManagement.allowsTool("plane", "list_work_items"));
        assertFalse(projectManagement.allowsTool("plane", "delete_project"));
        assertEquals("id,name,sequence_id,state,priority,assignees", projectManagement.defaultArguments("plane", "list_work_items").get("fields").asText());
        assertTrue(toolProfile(toolProfiles, SourceControlToolProfile.ID).allowsTool("gitea", "get_repository"));
        assertTrue(toolProfile(toolProfiles, TeamChatToolProfile.ID).allowsTool("mattermost", "post_message"));

        assertEquals(Set.of("low-pass", "high-pass"), lanes.stream().map(InferenceLaneDefinition::id).collect(Collectors.toSet()));
    }

    @Test
    void compositeProfilesUseAllThreeSharedServerDefinitionsAndExplicitAllowLists() {
        ToolProfile readonly = toolProfile(toolProfiles(), WorkspaceReadonlyToolProfile.ID);
        ToolProfile coordination = toolProfile(toolProfiles(), WorkspaceCoordinationToolProfile.ID);

        for (ToolProfile profile : List.of(readonly, coordination)) {
            assertEquals(SERVER_IDS, profile.mcpServers().keySet());
            assertEquals(SERVER_IDS, profile.allowedTools().keySet());
            assertTrue(profile.deniedTools().isEmpty());
            assertTrue(profile.allowAllToolServers().isEmpty());
        }
        assertEquals(4000, readonly.maxResultTokens());
        assertEquals(5000, coordination.maxResultTokens());
    }

    @Test
    void workspaceReadonlyRejectsWriteToolsFromAllSoftwareTeamInventories() {
        ToolProfile profile = toolProfile(toolProfiles(), WorkspaceReadonlyToolProfile.ID);

        assertRejected(profile, "plane", "create_project", "update_project", "delete_project",
                "create_work_item", "update_work_item", "delete_work_item",
                "create_work_item_comment", "update_work_item_comment", "delete_work_item_comment");
        assertRejected(profile, "gitea", "create_repo", "create_file", "update_file", "delete_file",
                "create_branch", "delete_branch", "pull_request_write", "create_release", "update_release", "delete_release",
                "issue_write", "label_write", "milestone_write", "timetracking_write");
        assertRejected(profile, "mattermost", "post_message", "update_message", "delete_message",
                "create_channel", "delete_channel", "leave_channel", "add_reaction", "create_bookmark");
    }

    @Test
    void workspaceCoordinationAllowsBoundedWritesAndRejectsSourceMutationAndIssueTracking() {
        ToolProfile profile = toolProfile(toolProfiles(), WorkspaceCoordinationToolProfile.ID);

        assertAllowed(profile, "plane", "create_project", "update_project", "create_work_item", "update_work_item",
                "create_work_item_comment", "update_work_item_comment", "create_work_item_link", "create_work_item_relation", "remove_work_item_relation");
        assertAllowed(profile, "gitea", "create_repo", "get_file_contents", "list_pull_requests", "pull_request_read");
        assertAllowed(profile, "mattermost", "create_direct_channel", "post_message", "update_message");

        assertRejected(profile, "gitea", "list_issues", "search_issues", "issue_read", "issue_write",
                "create_file", "update_file", "delete_file", "create_branch", "delete_branch",
                "pull_request_write", "create_pull_request", "update_pull_request", "merge_pull_request",
                "create_release", "update_release", "delete_release");
        assertRejected(profile, "mattermost", "delete_message", "delete_channel", "leave_channel", "add_reaction", "create_bookmark");
    }

    @Test
    void broadDomainProfilesRejectWorkflowConflictsAndKeepExpectedDefaults() {
        ToolProfile source = toolProfile(toolProfiles(), SourceControlToolProfile.ID);
        assertRejected(source, "gitea", "list_issues", "search_issues", "issue_read", "issue_write",
                "label_read", "label_write", "milestone_read", "milestone_write", "timetracking_read", "timetracking_write");
        assertRejected(source, "gitea", "delete_file", "delete_release", "delete_tag", "wiki_read", "wiki_write");
        assertAllowed(source, "gitea", "create_file", "update_file", "create_branch", "pull_request_write", "create_release", "get_release");

        ToolProfile project = toolProfile(toolProfiles(), ProjectManagementToolProfile.ID);
        assertRejected(project, "plane", "delete_project", "delete_work_item", "delete_work_item_comment",
                "delete_work_item_link", "delete_work_log", "delete_cycle", "delete_module", "delete_initiative",
                "delete_intake_work_item", "delete_label", "delete_work_item_property", "delete_work_item_type",
                "delete_state", "delete_epic", "delete_milestone", "get_project_worklog_summary", "list_work_logs",
                "create_work_log", "update_work_log", "list_intake_work_items", "create_intake_work_item",
                "retrieve_intake_work_item", "update_intake_work_item", "get_project_features", "update_project_features",
                "get_workspace_features", "update_workspace_features", "list_work_item_properties", "create_work_item_property",
                "retrieve_work_item_property", "update_work_item_property", "list_work_item_types", "create_work_item_type",
                "retrieve_work_item_type", "update_work_item_type", "retrieve_workspace_page", "retrieve_project_page",
                "create_workspace_page", "create_project_page");
        assertEquals("id,name,identifier", project.defaultArguments("plane", "list_projects").get("fields").asText());

        ToolProfile chat = toolProfile(toolProfiles(), TeamChatToolProfile.ID);
        assertRejected(chat, "mattermost", "delete_message", "delete_channel", "delete_post", "leave_channel", "mark_channel_viewed",
                "add_reaction", "get_reactions", "remove_reaction", "pin_message", "unpin_message", "create_bookmark",
                "delete_bookmark", "list_bookmarks", "update_bookmark", "update_bookmark_sort_order");
    }

    @Test
    void sharedMcpServerDefinitionsPreserveConnectionConfiguration() {
        McpServerDefinition plane = plane().definition();
        assertEquals("plane", plane.id());
        assertEquals("https://plane.example/mcp", plane.url());
        assertEquals("streamableHttp", plane.transportType());
        assertEquals("Authorization", plane.authHeaderName());
        assertEquals("Bearer %s", plane.authHeaderValueFormat());
        assertEquals("workspace-one", plane.extraHeaders().get("x-workspace-slug"));

        McpServerDefinition gitea = gitea().definition();
        assertEquals("https://gitea.example/mcp", gitea.url());
        assertEquals("Authorization", gitea.authHeaderName());
        assertEquals("token %s", gitea.authHeaderValueFormat());

        McpServerDefinition mattermost = mattermost().definition();
        assertEquals("https://mattermost.example/mcp", mattermost.url());
        assertEquals("Authorization", mattermost.authHeaderName());
        assertEquals("Bearer %s", mattermost.authHeaderValueFormat());
    }

    @Test
    void routinesUseExistingProfilesAndExpectedConsolidatedPrimitiveIds() throws Exception {
        String source = routineSources();
        assertFalse(source.contains(".toolProfile(\""));
        var matcher = Pattern.compile("\\.toolProfile\\(([^)]+)\\)").matcher(source);
        Set<String> constants = Set.of("WorkspaceReadonlyToolProfile.ID", "WorkspaceCoordinationToolProfile.ID", "ProjectManagementToolProfile.ID", "SourceControlToolProfile.ID", "TeamChatToolProfile.ID");
        int profileReferences = 0;
        while (matcher.find()) {
            profileReferences++;
            assertTrue(constants.contains(matcher.group(1)), "unexpected tool profile reference: " + matcher.group(1));
        }
        assertTrue(profileReferences > 0);

        assertTrue(source.contains("director-workspace-review"));
        assertTrue(source.contains("workspace-artifact-provisioning"));
        assertTrue(source.contains("collaboration-setup-message"));
        assertTrue(source.contains("pm-intake-review"));
        assertTrue(source.contains("pm-actionable-dev-task"));
        assertTrue(source.contains("po-canonical-acceptance-update"));
        assertTrue(source.contains("po-plane-handoff-update"));
        assertTrue(source.contains("dev-assigned-to-do-start"));
        assertTrue(source.contains("dev-source-context-plan"));
        assertTrue(source.contains("dev-plane-status-evidence-update"));

        for (String removed : List.of(
                "director-source-control-review", "director-project-management-review", "director-team-chat-review",
                "pm-wiki-basics-read", "wiki-repo-setup", "project-management-setup", "source-repository-plan",
                "project-basics-announcement", "collaboration-setup-gap", "wiki-project-index", "wiki-bootstrap-audit", "pm-wiki-status-sync",
                "pm-po-item-review", "pm-readiness-status", "pm-task-breakdown", "pm-task-sequencing", "pm-actionability-check",
                "po-wiki-basics-read", "po-scope-note", "po-gherkin-update", "po-decision-note", "po-priority-update", "po-pm-note-update",
                "dev-wiki-basics-read", "dev-wiki-closeout", "dev-to-do-review", "dev-start-to-do-status", "dev-wiki-engineering-read",
                "dev-source-repository-readiness", "dev-patch-plan", "dev-status-update", "dev-work-item-note")) {
            assertFalse(source.contains(removed), removed + " should have been removed");
        }
    }

    @Test
    void combinedRoutineStepsPreserveDecisionLoopAndThoughtBoundaries() throws Exception {
        assertOrdered(source("ProjectManagerRoutine.java"), "enterThought(\"pm-intake\"", "act(\"pm-intake-review\"", "decide(\"pm-work-intake-done\"", "exitThought(\"pm-intake\"");
        assertOrdered(source("ProjectManagerRoutine.java"), "enterThought(\"pm-planning\"", "act(\"pm-actionable-dev-task\"", "decide(\"pm-task-planning-done\"", "exitThought(\"pm-planning\"");
        assertOrdered(source("ProductOwnerRoutine.java"), "enterThought(\"po-acceptance\"", "act(\"po-canonical-acceptance-update\"", "decide(\"po-acceptance-done\"", "exitThought(\"po-acceptance\"");
        assertOrdered(source("ProductOwnerRoutine.java"), "enterThought(\"po-priority\"", "act(\"po-plane-handoff-update\"", "decide(\"po-priority-status-done\"", "exitThought(\"po-priority\"");
        assertOrdered(source("ApplicationDeveloperRoutine.java"), "enterThought(\"dev-selection\"", "act(\"dev-assigned-to-do-start\"", "decide(\"developer-work-selection-done\"", "exitThought(\"dev-selection\"");
        assertOrdered(source("ApplicationDeveloperRoutine.java"), "act(\"dev-plane-status-evidence-update\"", "direct:act");
    }

    @Test
    void workspaceHtmlRendersProfilePolicyInformation() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/META-INF/resources/workspace.html"));

        assertTrue(html.contains("policy-mode"));
        assertTrue(html.contains("explicit allow list"));
        assertTrue(html.contains("broad access"));
        assertTrue(html.contains("Allowed tools"));
        assertTrue(html.contains("Denied tools"));
        assertTrue(html.contains("Default arguments"));
        assertTrue(html.contains("Prompt guidance"));
        assertTrue(html.contains("max ${esc(p.maxResultTokens || 0)} tokens"));
        assertTrue(html.contains("camel-tack-logo.png"));
        assertTrue(html.contains("the stuff you need to ride"));
        assertTrue(html.contains("href=\"#projections-section\""));
        assertTrue(html.contains("href=\"#personas-routines-section\""));
        assertTrue(html.contains("href=\"#inference-infra-section\""));
        assertTrue(html.contains("href=\"#tools-section\""));
        assertTrue(html.contains("id=\"toolServers\""));
        assertTrue(html.contains("function renderToolServers"));
        assertTrue(html.contains("function renderSafeIdentities"));
        assertTrue(html.contains("function renderLatestResult"));
        assertTrue(html.contains("function renderTimeline"));
        assertTrue(html.contains("tool-profile:${p.id}:guidance"));
        assertTrue(html.contains("DETAILS_STORAGE_KEY"));
        assertFalse(html.contains("Raw profile JSON"));
        assertFalse(html.contains("json(p.identities)"));
    }

    @Test
    void softwareTeamDirectorSchemaIsPackagedForDocumentationAndCatalogUse() throws Exception {
        try (var input = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("schemas/example/software-team/agent-run-requests-schema.json")) {
            assertNotNull(input);
            String schema = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(schema.contains("agentRuns"));
            assertTrue(schema.contains("personaId"));
            assertTrue(schema.contains("routineId"));
        }
    }

    private static List<ToolProfile> toolProfiles() {
        PlaneMcpServer plane = plane();
        GiteaMcpServer gitea = gitea();
        MattermostMcpServer mattermost = mattermost();
        ArrayList<ToolProfile> profiles = new ArrayList<>();
        profiles.addAll(new WorkspaceReadonlyToolProfile(plane, gitea, mattermost, 4000).toolProfiles());
        profiles.addAll(new WorkspaceCoordinationToolProfile(plane, gitea, mattermost, 5000).toolProfiles());
        profiles.addAll(new TeamChatToolProfile(mattermost, 3000).toolProfiles());
        profiles.addAll(new ProjectManagementToolProfile(plane, 5000).toolProfiles());
        profiles.addAll(new SourceControlToolProfile(gitea, 6000).toolProfiles());
        return List.copyOf(profiles);
    }

    private static PlaneMcpServer plane() {
        return new PlaneMcpServer("https://plane.example/mcp", "workspace-one");
    }

    private static GiteaMcpServer gitea() {
        return new GiteaMcpServer("https://gitea.example/mcp");
    }

    private static MattermostMcpServer mattermost() {
        return new MattermostMcpServer("https://mattermost.example/mcp");
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

    private static void assertAllowed(ToolProfile profile, String server, String... tools) {
        for (String tool : tools) {
            assertTrue(profile.allowsTool(server, tool), profile.id() + " should allow " + server + "." + tool);
        }
    }

    private static void assertRejected(ToolProfile profile, String server, String... tools) {
        for (String tool : tools) {
            assertFalse(profile.allowsTool(server, tool), profile.id() + " should reject " + server + "." + tool);
        }
    }

    private static String routineSources() throws Exception {
        return source("DirectorRoutine.java") + source("ProjectManagerRoutine.java")
                + source("ProductOwnerRoutine.java") + source("ApplicationDeveloperRoutine.java");
    }

    private static String source(String fileName) throws Exception {
        return Files.readString(Path.of("src/main/java/us/nusun/llmshare/example/routine", fileName));
    }

    private static void assertOrdered(String text, String... needles) {
        int cursor = -1;
        for (String needle : needles) {
            int next = text.indexOf(needle, cursor + 1);
            assertTrue(next > cursor, "Expected order token not found after previous token: " + needle);
            cursor = next;
        }
    }
}
