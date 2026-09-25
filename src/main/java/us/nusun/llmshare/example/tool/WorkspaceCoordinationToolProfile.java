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
    private final GiteaMcpServer gitea;
    private final MattermostMcpServer mattermost;
    private final int maxResultTokens;

    @Inject
    public WorkspaceCoordinationToolProfile(
            PlaneMcpServer plane,
            GiteaMcpServer gitea,
            MattermostMcpServer mattermost,
            @ConfigProperty(name = "agent.tool.profile.workspace-coordination.max-result-tokens", defaultValue = "5000") int maxResultTokens) {
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
                        "create_project",
                        "retrieve_project",
                        "update_project",
                        "get_project_members",
                        "add_project_members",
                        "update_project_member",
                        "remove_project_member",
                        "get_workspace_members",
                        "list_work_items",
                        "create_work_item",
                        "retrieve_work_item",
                        "retrieve_work_item_by_identifier",
                        "update_work_item",
                        "search_work_items",
                        "list_work_item_activities",
                        "list_work_item_comments",
                        "retrieve_work_item_comment",
                        "create_work_item_comment",
                        "update_work_item_comment",
                        "list_work_item_links",
                        "create_work_item_link",
                        "update_work_item_link",
                        "list_work_item_relations",
                        "create_work_item_relation",
                        "remove_work_item_relation",
                        "list_labels",
                        "list_states")
                .allowTools(GiteaMcpServer.ID,
                        "get_me",
                        "list_my_repos",
                        "list_org_repos",
                        "search_repos",
                        "create_repo",
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
                        "list_public_channels",
                        "get_channel",
                        "get_channel_by_name",
                        "get_channel_members",
                        "add_channel_member",
                        "add_user_to_channel",
                        "remove_channel_member",
                        "remove_user_from_channel",
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
                        - search direct names before creating artifacts; create or update only the artifact named by the current step, then stop
                        - Plane is the sole system for projects, planned work, assignments, status, blockers, dependencies, and priorities
                        - never create or manage planned work through Gitea issues
                        - use Gitea only for repository discovery, repository creation, and implementation evidence; the canonical Gitea web/clone base is https://gitea.apps.cluster.lab.nusun.us
                        - when constructing repository links or clone URLs, use https://gitea.apps.cluster.lab.nusun.us rather than github.com or an invented/internal hostname
                        - CI/workflow execution is not enabled in this Gitea environment; do not inspect or rely on Actions/workflows or CI checks
                        - use Mattermost for concise questions, handoffs, and notifications; keep Plane project and Mattermost channel membership aligned with the current team
                        - link to durable Plane or Gitea artifacts instead of duplicating their contents; update existing artifacts rather than creating near-duplicates

                        SKILL: mission-anchor-reuse
                        - once a Plane project UUID, work-item UUID/identifier, Gitea owner/repo, Mattermost team id, or channel id is verified, reuse that exact locator
                        - never shorten or reconstruct UUIDs; do not relist known resources unless direct lookup fails or fresh evidence says the anchor is stale

                        SKILL: plane-work-item-navigation
                        - scope work-item calls to the verified project id; prefer retrieve_work_item_by_identifier for human keys and complete UUIDs only for retrieve_work_item
                        - use sparse list_work_items for discovery, then retrieve only the selected rich item when needed
                        - on rich-response shape failure, use sparse evidence where sufficient instead of retrying the same failing shape

                        SKILL: plane-membership-model
                        - keep three sets distinct: CURRENT MISSION ROSTER = active AgentRuns; Plane workspace members = Plane accounts available for project membership; Plane project members = accounts actually granted access to this project
                        - never infer workspace membership from the mission roster, never infer project membership from workspace membership, and never infer an active AgentRun from either Plane set
                        - for onboarding, resolve each CURRENT MISSION ROSTER Plane identity against get_workspace_members, compare those resolved accounts with get_project_members, and add every resolved current-roster account missing from the project
                        - a teammate who started after bootstrap is expected to already have a Plane workspace account; absence from an earlier roster says nothing about whether that account exists
                        - do not declare onboarding complete from an old project-member list; freshly compare the CURRENT MISSION ROSTER with current project/channel membership
                        - when reporting, state exactly which current-roster identities were resolved in the workspace, already project members, newly added, or unresolved; never claim that non-roster personas do not exist in the workspace unless directly verified and relevant

                        SKILL: bounded-platform-navigation
                        - known Gitea PR/file -> exact lookup; repository-root discovery -> get_repository_tree, not a guessed '.' path
                        - known Mattermost channel -> channel id and recent bounded messages/thread; search broadly only when location is unknown
                        - stop once the current operation has enough evidence

                        SKILL: capability-aware-recovery
                        - validation error -> correct arguments once; 404 -> resolve exact locator once; response-shape failure -> switch to sparse/supported representation
                        - do not repeat an equivalent failed call without new evidence
                        """)
                .defaultArgument(PlaneMcpServer.ID, "list_projects", "fields", "id,name,identifier")
                .defaultArgument(PlaneMcpServer.ID, "list_work_items", "fields", "id,name,sequence_id,state,priority,assignees")
                .defaultArgument(PlaneMcpServer.ID, "list_work_items", "per_page", 12)
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
