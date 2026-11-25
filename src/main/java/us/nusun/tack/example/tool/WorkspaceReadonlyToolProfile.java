package us.nusun.tack.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import us.nusun.tack.framework.tool.ToolProfileProvider;
import us.nusun.tack.framework.tool.ToolProfile;

import java.util.List;

@ApplicationScoped
public class WorkspaceReadonlyToolProfile implements ToolProfileProvider {
    public static final String ID = "workspace-readonly";

    private final KaneoMcpServer kaneo;
    private final GiteaMcpServer gitea;
    private final MattermostMcpServer mattermost;

    @Inject
    public WorkspaceReadonlyToolProfile(
            KaneoMcpServer kaneo,
            GiteaMcpServer gitea,
            MattermostMcpServer mattermost) {
        this.kaneo = kaneo;
        this.gitea = gitea;
        this.mattermost = mattermost;
    }

    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder(ID)
                .mcpServer(kaneo.definition())
                .mcpServer(gitea.definition())
                .mcpServer(mattermost.definition())
                .allowTools(KaneoMcpServer.ID,
                        "whoami",
                        "list_workspaces",
                        "list_workspace_members",
                        "list_projects",
                        "get_project",
                        "list_project_columns",
                        "list_tasks",
                        "get_task",
                        "get_task_by_ticket_id",
                        "get_task_relations",
                        "list_task_comments",
                        "list_task_activity")
                .allowTools(GiteaMcpServer.ID,
                        "get_me",
                        "search_repos",
                        "get_repository_tree",
                        "get_dir_contents",
                        "get_file_contents",
                        "list_branches",
                        "list_commits",
                        "get_commit",
                        "list_pull_requests",
                        "pull_request_read")
                .allowTools(MattermostMcpServer.ID,
                        "get_me",
                        "list_teams",
                        "list_my_channels",
                        "get_channel",
                        "get_channel_by_name",
                        "get_channel_members",
                        "get_channel_messages",
                        "get_thread",
                        "search_messages",
                        "get_user",
                        "get_user_by_username",
                        "search_users")
                .promptGuidance(KaneoWorkGuidance.DISCOVERY + """
                        - Match artifacts to the current mission and reuse verified locators. Read the exact Gitea repository,
                          file or PR, or the Mattermost channel by team and name.
                        - Kaneo owns work, Gitea owns source and PR state, and the mission roster owns active AgentRuns.
                          Distinguish verified, missing, and unknown evidence. Gitea CI is disabled.
                        """)
                .build());
    }
}
