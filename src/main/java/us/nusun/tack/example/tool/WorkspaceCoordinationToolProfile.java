package us.nusun.tack.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import us.nusun.tack.framework.tool.ToolProfileProvider;
import us.nusun.tack.framework.tool.ToolProfile;

import java.util.List;

@ApplicationScoped
public class WorkspaceCoordinationToolProfile implements ToolProfileProvider {
    public static final String ID = "workspace-coordination";

    private final KaneoMcpServer kaneo;
    private final MattermostMcpServer mattermost;

    @Inject
    public WorkspaceCoordinationToolProfile(
            KaneoMcpServer kaneo,
            MattermostMcpServer mattermost) {
        this.kaneo = kaneo;
        this.mattermost = mattermost;
    }

    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder(ID)
                .mcpServer(kaneo.definition())
                .mcpServer(mattermost.definition())
                .allowTools(KaneoMcpServer.ID,
                        "whoami",
                        "list_workspaces",
                        "list_workspace_members",
                        "list_projects",
                        "get_project",
                        "create_project",
                        "update_project",
                        "list_project_columns")
                .allowTools(MattermostMcpServer.ID,
                        "get_me",
                        "list_teams",
                        "list_my_channels",
                        "list_public_channels",
                        "get_channel",
                        "get_channel_by_name",
                        "create_channel",
                        "get_channel_members",
                        "add_channel_member",
                        "add_user_to_channel",
                        "get_channel_messages",
                        "get_thread",
                        "search_messages",
                        "post_message",
                        "update_message",
                        "get_user",
                        "get_user_by_username",
                        "search_users")
                .promptGuidance("""
                        - Resolve the Tack Software Team workspace by exact name and copy its ID verbatim, including
                          case and repeated characters. Find the project by its mission anchor and the Mattermost channel
                          by team and name before creating either. Use a unique project key and the default columns.
                          Keep the project private.
                        - Verify inherited access using the workspace member roster. Report missing Kaneo access as a
                          provisioning blocker. Reconcile Mattermost channel membership with the active mission roster.
                        """)
                .build());
    }
}
