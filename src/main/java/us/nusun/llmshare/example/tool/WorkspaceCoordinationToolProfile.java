package us.nusun.llmshare.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.framework.inventory.ToolProfileProvider;
import us.nusun.llmshare.framework.model.ToolProfile;

import java.util.List;

@ApplicationScoped
public class WorkspaceCoordinationToolProfile implements ToolProfileProvider {
    public static final String ID = "workspace-coordination";

    private final PlaneMcpServer plane;
    private final MattermostMcpServer mattermost;
    private final int maxResultTokens;

    @Inject
    public WorkspaceCoordinationToolProfile(
            PlaneMcpServer plane,
            MattermostMcpServer mattermost,
            @ConfigProperty(name = "agent.tool.profile.workspace-coordination.max-result-tokens", defaultValue = "5000")
            int maxResultTokens) {
        this.plane = plane;
        this.mattermost = mattermost;
        this.maxResultTokens = maxResultTokens;
    }

    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder(ID)
                .mcpServer(plane.definition())
                .mcpServer(mattermost.definition())
                .allowTools(PlaneMcpServer.ID,
                        "get_me",
                        "list_projects",
                        "create_project",
                        "retrieve_project",
                        "update_project",
                        "get_project_members",
                        "add_project_members",
                        "get_workspace_members",
                        "list_states",
                        "create_state")
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
                        - this profile creates/reconciles the shared Plane project, lifecycle states, Mattermost channel,
                          and project/channel membership; the calling routine defines when and what to reconcile
                        - reuse verified project/team/channel/member ids instead of rediscovering them
                        - before creating a project or channel, resolve the direct expected name and avoid duplicates
                        - for Mattermost, reuse a verified channel id; otherwise list teams once and use the returned team id
                          with get_channel_by_name before creating anything
                        - keep active roster identity, Plane workspace membership, Plane project membership, and Mattermost
                          channel membership distinct; resolve the specific identity/member set requested by the routine
                        - after membership additions, re-read the relevant membership set to verify the mutation
                        - this profile cannot remove project/channel members; never imply removal occurred
                        - create Plane states only when the calling routine specifies the required state name/group and a
                          matching state is verified missing
                        - do not infer active AgentRuns from external membership
                        - stop after the requested coordination mutation is verified
                        """)
                .defaultArgument(PlaneMcpServer.ID, "list_projects", "fields", "id,name,identifier")
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
