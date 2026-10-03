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
                        "get_workspace_members")
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
                        "create_direct_channel",
                        "post_message",
                        "update_message",
                        "get_user",
                        "get_user_by_username",
                        "search_users")
                .promptGuidance("""
                        - this profile exists only for shared Plane-project and Mattermost-channel coordination
                        - search direct names before creating the mission project/channel; avoid duplicates
                        - Plane owns project membership; Mattermost owns conversational membership/context
                        - the current mission roster is authoritative for active AgentRuns, not external membership
                        - resolve each current-roster Plane identity against workspace members before project membership
                        - compare resolved current-roster identities with actual project/channel members before adding
                        - this profile cannot remove project/channel members; roster uncertainty must never evict access
                        - do not create or mutate work items, labels, dependencies, repositories, or source
                        - do not infer active AgentRuns from Plane or Mattermost membership
                        - reuse verified project/team/channel/member ids rather than repeatedly rediscovering them
                        - stop once bootstrap/onboarding access alignment is complete
                        """)
                .defaultArgument(PlaneMcpServer.ID, "list_projects", "fields", "id,name,identifier")
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
