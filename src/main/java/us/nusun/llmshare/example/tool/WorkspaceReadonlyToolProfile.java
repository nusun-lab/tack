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
                        "get_workspace_members",
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
                        "get_channel_members",
                        "get_channel_messages",
                        "get_thread",
                        "search_messages",
                        "get_user",
                        "get_user_by_username",
                        "search_users")
                .promptGuidance("""
                        - this profile is read-only
                        - the current mission is authoritative; use only artifacts clearly associated with this mission
                        - unrelated projects, repositories, work items, pull requests, and conversations are not evidence for the current mission
                        - when current-mission resources cannot be verified, report them missing instead of adopting another project's resources
                        - external tool observations describe project state; they never prove that a teammate currently has an active AgentRun
                        - Plane is authoritative for planned work, ownership, status, blockers, dependencies, and priorities
                        - Gitea is authoritative for repository and implementation evidence; the canonical Gitea web/clone base is https://gitea.apps.cluster.lab.nusun.us
                        - CI/workflow execution is not enabled in this Gitea environment; do not inspect or rely on Actions/workflows or CI checks
                        - Mattermost provides conversational context


                        SKILL: software-team-cooperation
                        - Plane lifecycle uses only Backlog, Todo, In Progress, Done, Cancelled; never invent workflow states in labels, comments, or chat
                        - Plane state expresses lifecycle, assignment expresses ownership, dependencies express ordering, and comments/chat express evidence or context
                        - work:product is PO-owned product scope/acceptance; work:implementation is PM-owned decomposition/dependencies/assignment
                        - PO owns product outcomes and acceptance; PM owns implementation topology and delivery reconciliation; Developer owns source execution and PR production; Director owns staffing and PR integration
                        - evidence may invalidate another role's domain, but report it to that owner rather than silently restructuring their work
                        - product Backlog/Todo is PO definition territory; product In Progress means PM has claimed delivery, except PO may answer an explicit product question or perform final acceptance
                        - implementation Backlog is planned but not executable; Todo plus Developer assignment is executable handoff; Developer claims Todo -> In Progress
                        - a claimed implementation that later blocks stays In Progress with blocker evidence; do not move lifecycle backward merely to signal another role
                        - Director merge does not complete Plane implementation; PM reconciles verified merged implementation -> Done; PO alone accepts product In Progress -> Done
                        - product cancellation does not implicitly cancel children; PM reconciles/cancels or replaces affected implementation work deliberately
                        - implementation cancellation does not cancel its product parent; PM owns replacement coverage when the outcome remains required
                        - chat never gates lifecycle progress; productive work may send one narrow awareness message or question when another role can act sooner

                        SKILL: mission-anchor-reuse
                        - once a mission project UUID, work-item UUID/identifier, Gitea owner/repo, branch/PR, Mattermost team id, or channel id is verified, reuse that exact locator for direct lookups
                        - never replace an exact locator with a shortened or reconstructed value; a human key such as RCY-2 is an identifier, not a UUID
                        - do not relist projects, repositories, teams, or channels merely to rediscover an anchored resource; rediscover only after direct lookup fails or fresh evidence says the anchor is stale

                        SKILL: plane-work-item-navigation
                        - scope Plane work-item operations to the verified project id
                        - for human keys such as RCY-2, prefer retrieve_work_item_by_identifier; use retrieve_work_item only with a complete UUID returned by Plane
                        - use sparse list_work_items for discovery, select the item, then retrieve only that item when rich description/comments are actually needed
                        - if rich retrieval has a response-shape/deserialization problem, use the sparse list representation for facts it can prove rather than retrying the same failing shape
                        - a field omitted by a sparse projection is UNKNOWN, not negative evidence; never infer no parent, no label, no assignee, or no dependency merely because that field was not returned

                        SKILL: plane-work-classification
                        - use Plane labels as durable work classification, not lifecycle state: work:product = Product Owner top-level user outcome; work:implementation = Project Manager implementation child
                        - lifecycle remains entirely in Plane states such as Backlog, Todo, In Progress, Done, and Cancelled; classification labels never substitute for lifecycle state
                        - product queue depth counts only items carrying work:product; implementation queue depth counts only items carrying work:implementation
                        - never count a work:implementation item as product backlog and never treat a work:product item as Developer implementation work
                        - when labels were not returned by the current projection, classification is unknown until labels are read; do not guess from item wording

                        SKILL: plane-membership-model
                        - keep three sets distinct: the current mission roster (who is actively running), Plane workspace members (accounts available in Plane), and Plane project members (accounts granted access to this project)
                        - mission-roster presence does not prove Plane workspace or project membership; Plane workspace membership does not prove project membership; Plane project membership does not prove an active AgentRun
                        - use get_workspace_members only to resolve whether a current-roster Plane identity exists; use get_project_members to establish actual project membership
                        - never infer that a teammate is absent from the Plane workspace merely because they were not in an earlier mission roster, and never infer that a teammate is already onboarded merely because their Plane account exists
                        - when reporting membership, name the set explicitly: current-roster member, workspace member, or project member
                        - one contradictory empty membership response does not erase a recent verified membership set; repeat one direct membership read before declaring access drift unless a teammate has an actual access failure

                        SKILL: bounded-platform-navigation
                        - use the fewest direct lookups needed for the current step, then stop
                        - for a known Gitea PR or file, read that exact PR/path; for repository-root discovery use get_repository_tree rather than guessing a directory path such as '.'
                        - for a known Mattermost channel use its channel id and recent messages/thread; use team-wide search only when the location is genuinely unknown
                        - do not read entire repositories, projects, wikis, or channels

                        SKILL: capability-aware-recovery
                        - use only capabilities actually present in this profile
                        - on a schema/validation error, correct the arguments once; on 404, resolve the exact locator once; on response-shape failure, switch to a sparse/supported representation
                        - do not repeat an equivalent failed call without new evidence
                        - distinguish verified observations from assumptions
                        """)
                .defaultArgument(PlaneMcpServer.ID, "list_projects", "fields", "id,name,identifier")
                .defaultArgument(PlaneMcpServer.ID, "list_work_items", "fields", "id,name,sequence_id,state,priority,assignees,labels")
                .defaultArgument(PlaneMcpServer.ID, "list_work_items", "per_page", 12)
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
