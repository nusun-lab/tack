package us.nusun.llmshare.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.framework.inventory.ToolProfileProvider;
import us.nusun.llmshare.framework.model.ToolProfile;

import java.util.List;

@ApplicationScoped
public class WorkspaceReadonlyToolProfile implements ToolProfileProvider {
    public static final String ID = "workspace-readonly";

    private final PlaneMcpServer plane;
    private final GiteaMcpServer gitea;
    private final MattermostMcpServer mattermost;
    private final int maxResultTokens;

    @Inject
    public WorkspaceReadonlyToolProfile(
            PlaneMcpServer plane,
            GiteaMcpServer gitea,
            MattermostMcpServer mattermost,
            @ConfigProperty(name = "agent.tool.profile.workspace-readonly.max-result-tokens", defaultValue = "4000") int maxResultTokens) {
        this.plane = plane;
        this.gitea = gitea;
        this.mattermost = mattermost;
        this.maxResultTokens = maxResultTokens;
    }

    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder(ID)
                .mcpServer(plane.definition())
                .mcpServer(gitea.definition())
                .mcpServer(mattermost.definition())
                .allowTools(PlaneMcpServer.ID,
                        "get_me",
                        "list_projects",
                        "retrieve_project",
                        "get_project_members",
                        "list_work_items",
                        "retrieve_work_item",
                        "retrieve_work_item_by_identifier",
                        "search_work_items",
                        "list_work_item_activities",
                        "list_work_item_comments",
                        "retrieve_work_item_comment",
                        "list_labels",
                        "list_states")
                .allowTools(GiteaMcpServer.ID,
                        "get_me",
                        "list_my_repos",
                        "list_org_repos",
                        "search_repos",
                        "get_repository_tree",
                        "get_dir_contents",
                        "get_file_contents",
                        "list_branches",
                        "list_commits",
                        "get_commit",
                        "list_pull_requests",
                        "pull_request_read",
                        "list_releases",
                        "get_latest_release",
                        "get_release")
                .allowTools(MattermostMcpServer.ID,
                        "get_me",
                        "list_teams",
                        "list_my_channels",
                        "get_channel",
                        "get_channel_by_name",
                        "get_channel_messages",
                        "get_thread",
                        "search_messages",
                        "get_user",
                        "get_user_by_username",
                        "search_users")
                .promptGuidance("""
                        - this profile is read-only
                        - use the fewest direct lookups needed for the current step, then stop
                        - Plane is authoritative for planned work, ownership, status, and blockers
                        - Gitea is authoritative for repository and implementation evidence
                        - CI/workflow execution is not enabled in this Gitea environment; do not inspect or rely on Actions/workflows or CI checks
                        - Mattermost provides conversational context
                        - prefer direct ids, links, and recent bounded reads over broad searches
                        - do not read entire repositories, projects, wikis, or channels
                        - distinguish verified observations from assumptions
                        """)
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
