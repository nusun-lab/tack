package us.nusun.tack.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.tack.framework.tool.ToolProfileProvider;
import us.nusun.tack.framework.tool.ToolProfile;

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
                        "project",
                        "member",
                        "state")
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
                          and project/channel membership
                        - use only these Plane actions here: project=list/create/retrieve/update;
                          member=me/list_workspace/list_project/add_project; state=list/create
                        - reuse verified project/team/channel/member ids instead of rediscovering them
                        - before creating a project or channel, resolve the direct expected name and avoid duplicates
                        - for Mattermost, reuse a verified channel id; otherwise list teams once and use the returned team id
                          with get_channel_by_name before creating anything
                        - keep active roster identity, Plane workspace membership, Plane project membership, and Mattermost
                          channel membership distinct; resolve the specific identity/member set requested by the routine
                        - for Plane project membership, use member(action="list_workspace") to resolve the workspace user and
                          member(action="list_project", project_id=<verified>) to verify whether that user already has access
                        - when project access is missing, use member(action="add_project", project_id=<verified>,
                          member_id=<workspace user UUID>, role="contributor"); member_id is the workspace user UUID
                        - member(action="list_project") returns project-member users keyed by workspace user UUID; it does not
                          expose the underlying membership row id
                        - after membership additions, re-read member(action="list_project", project_id=<verified>) to verify
                          the mutation; this routine should not update/remove project or channel members even though the
                          resource tool supports additional actions
                        - create Plane states only when the calling routine specifies the required state name/group and a
                          matching state is verified missing; use state(action="list", project_id=<verified>) and
                          state(action="create", project_id=<verified>, ...)
                        - do not infer active AgentRuns from external membership
                        - stop after the requested coordination mutation is verified
                        """)
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
