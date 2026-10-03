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
                        - this profile exists only for shared Plane-project and Mattermost-channel coordination
                        - mission collaboration uses one private current-mission team channel; reconcile that shared channel rather than substituting a direct channel for ordinary team coordination
                        - resolve Mattermost in this order: reuse a verified channel id; otherwise list_teams once, use that returned team id with get_channel_by_name for the mission channel name, and create only after that direct lookup proves it missing
                        - use list_my_channels only when the mission channel name is genuinely unknown; values shown only in tool descriptions/examples are not verified ids
                        - search direct names before creating the mission project/channel; avoid duplicates
                        - Plane owns project membership and canonical lifecycle-state bootstrap; Mattermost owns
                          conversational membership/context
                        - bootstrap must verify Backlog, Todo, In Progress, Done, and Cancelled exist; create only
                          missing canonical states and never duplicate an existing state with the same purpose
                        - the current mission roster is authoritative for active AgentRuns, not external membership
                        - any roster change invalidates previously verified Plane-project/Mattermost-channel parity until both membership sets are checked again
                        - resolve each current-roster Plane identity against workspace members before project membership
                        - compare resolved current-roster identities with actual project/channel members before adding
                        - after additions, re-read both membership sets and verify full current-roster parity before
                          reporting onboarding complete; newly active teammates are not operationally ready until verified
                        - this profile cannot remove project/channel members; roster uncertainty must never evict access
                        - do not create or mutate work items, labels, dependencies, repositories, or source
                        - state creation is limited to missing canonical bootstrap states for the mission project
                        - do not infer active AgentRuns from Plane or Mattermost membership
                        - reuse verified project/team/channel/member ids rather than repeatedly rediscovering them
                        - stop once bootstrap/onboarding access alignment is complete
                        """)
                .defaultArgument(PlaneMcpServer.ID, "list_projects", "fields", "id,name,identifier")
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
