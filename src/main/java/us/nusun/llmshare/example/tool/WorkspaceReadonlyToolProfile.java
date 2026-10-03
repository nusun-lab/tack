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
                .promptGuidance("""
                        - this profile is read-only
                        - the current mission is authoritative; use only artifacts clearly associated with this mission
                        - unrelated projects, repositories, work items, pull requests, and conversations are not evidence for the current mission
                        - when current-mission resources cannot be verified, report them missing instead of adopting another project's resources
                        - external tool observations describe project state; they never prove that a teammate currently has an active AgentRun
                        - Plane is authoritative for planned work, ownership, status, blockers, dependencies, and priorities
                        - Gitea is authoritative for repository and implementation evidence; the canonical Gitea web/clone base is https://gitea.apps.cluster.lab.nusun.us
                        - CI/workflow execution is not enabled in this Gitea environment; do not inspect or rely on Actions/workflows or CI checks
                        - Mattermost provides conversational context; current-mission shared-channel history is the default source for fresh team exchanges
                        - do not use long-lived direct-message history as current-mission evidence unless the exchange is intentionally private and explicitly tied to this mission


                        SKILL: software-team-cooperation
                        - Plane lifecycle uses only Backlog, Todo, In Progress, Done, Cancelled; never invent workflow states in labels, comments, or chat
                        - Plane state expresses lifecycle, assignment expresses ownership, dependencies express ordering,
                          and comments/chat express evidence or context
                        - Backlog may be unassigned; every Todo, In Progress, Done, or Cancelled item must have an owner
                          appropriate to its verified classification
                        - top-level Plane work items labeled type:epic are PO-owned product outcomes and acceptance
                          containers; non-Backlog Epics must be assigned to PO and are never engineer source work
                        - type:story + work:implementation is PM-coordinated delivery; type:task + work:test is QA-owned test engineering
                        - type:bug + work:defect is QA-recorded product failure evidence; new defects begin unparented
                        - PO classifies each defect into the correct Epic and owns product priority before PM routes repair
                        - PO owns each Epic through acceptance; PM owns delivery flow, Story topology, and defect routing
                        - Developer owns assigned component Story/Bug execution
                        - Architect owns repository architecture, integration, conflicts, and architecture-heavy Story/Bug execution
                        - QA owns system behavior tests, evidence, defect recording, and defect retest closure; PO alone accepts an Epic Done
                        - evidence may invalidate another role's domain, but report it to that owner rather than silently restructuring their work
                        - implementation Backlog is planned but not executable; Todo plus Developer assignment is executable handoff; Developer claims Todo -> In Progress
                        - test Backlog is planned QA work; Todo plus QA assignment is executable handoff; QA claims Todo -> In Progress
                        - claimed work that later blocks stays In Progress with blocker evidence; do not move lifecycle
                          backward merely to signal another role
                        - AgentRun presence is ephemeral execution capacity, never durable lifecycle truth; temporary
                          absence never moves Todo, In Progress, Done, or Cancelled work backward
                        - Done and Cancelled are terminal for ordinary flow management; reopening requires an explicit
                          domain-owned exception such as PO defect-driven Epic reopening
                        - Epic lifecycle separates readiness from consumption: Todo is ready/plannable, while In Progress
                          means delivery is actively consuming the Epic; normal active Epic depth is at most three
                        - implementation children beneath Todo Epics stay Backlog until the parent Epic is In Progress
                        - maintain practical flow buffers of roughly 2-3 useful active units plus 2-3 executable queued
                          units per delivery role when independent work exists; waiting on integration/deployment/another
                          role does not justify idling on unrelated executable work, and 2-3 is not a hard ceiling
                        - Architect merge does not itself complete Plane lifecycle: the assigned engineer closes merged work:implementation, while QA closes repaired work:defect after retest
                        - an Epic is not complete merely because implementation merged; PO requires QA evidence and no relevant open defect
                        - chat never gates lifecycle progress; productive work may send one narrow awareness message or question when another role can act sooner

                        SKILL: mission-anchor-reuse
                        - once a mission project UUID, work-item UUID/identifier, Gitea owner/repo, branch/PR, Mattermost team id, or channel id is verified, reuse that exact locator for direct lookups
                        - never replace an exact locator with a shortened or reconstructed value; a human key such as RCY-2 is an identifier, not a UUID
                        - ids, names, URLs, and other values shown only in tool descriptions/examples are illustrative and are never verified resource locators
                        - do not relist projects, repositories, teams, or channels merely to rediscover an anchored resource; rediscover only after direct lookup fails or fresh evidence says the anchor is stale

                        SKILL: plane-work-item-navigation
                        - scope Plane work-item operations to the verified project id
                        - for human keys such as RCY-2, resolve the UUID once, then use the exact recipes below
SKILL: plane-query-recipes
                                                - Discovery recipe: list_work_items(project_id=<verified>, fields="id,name,sequence_id,state,priority,assignees").
                                                  Use this sparse queue view for lifecycle, priority, and ownership. Do not add labels or parent.
                                                - Classification recipe: retrieve_work_item(project_id=<verified>, work_item_id=<selected UUID>,
                                                  expand="labels", fields="id,name,sequence_id,state,labels"). Always include exactly these fields
                                                  for classification. Never omit fields and never include assignees in this call.
                                                - Description recipe: retrieve_work_item(project_id=<verified>, work_item_id=<selected UUID>,
                                                  fields="id,name,sequence_id,state,description_html"). Do not include labels or assignees.
                                                - Parent recipe: retrieve_work_item(project_id=<verified>, work_item_id=<selected UUID>,
                                                  fields="id,name,sequence_id,state,parent"). Never expand parent.
                                                - Ownership and classification are deliberately separate calls. Never attempt to retrieve
                                                  assignees and expanded labels in the same WorkItemDetail response.
                                                - If one exact recipe fails with a WorkItemDetail assignee/label/parent shape error, stop that
                                                  retrieval strategy for the pass. Never repeat an equivalent failing shape on another item.
                        
                        - a field omitted by a sparse projection is UNKNOWN, not negative evidence; never infer no parent,
                          no label, no assignee, or no dependency merely because that field was not returned

                        SKILL: plane-work-classification
                        - Tack work-item kinds are represented by exactly one attached type:* label, not native Plane work-item types
                        - type:epic = product outcome; type:story = implementation delivery; type:task = QA test engineering; type:bug = defect evidence
                        - work:implementation, work:test, and work:defect are additional durable flow classifications, never lifecycle states
                        - lifecycle remains entirely in Plane states Backlog, Todo, In Progress, Done, and Cancelled
                        - implementation queue depth counts type:story + work:implementation; QA queue depth counts type:task + work:test
                        - work items retain parent/child epic association so delivery and evidence can be evaluated against product intent
                        - when labels or parent were not returned, classification/parentage is unknown until read; never guess from title, wording, state, hierarchy, or role

                        SKILL: plane-membership-model
                        - keep three sets distinct: the current mission roster (who is actively running), Plane workspace members (accounts available in Plane), and Plane project members (accounts granted access to this project)
                        - mission-roster presence does not prove Plane workspace or project membership; Plane workspace membership does not prove project membership; Plane project membership does not prove an active AgentRun
                        - use get_workspace_members only to resolve whether a current-roster Plane identity exists; use get_project_members to establish actual project membership
                        - never infer that a teammate is absent from the Plane workspace merely because they were not in an earlier mission roster, and never infer that a teammate is already onboarded merely because their Plane account exists
                        - when reporting membership, name the set explicitly: current-roster member, workspace member, or project member
                        - for PM onboarding observations, compare the authoritative active roster directly with current
                          Plane project members and current shared-channel members; do not use prior memory as proof of parity
                        - if an active identity is absent from either current membership set, onboarding is incomplete
                        - one contradictory empty membership response should be verified with one narrow direct re-read
                          before declaring drift unless a teammate has an actual access failure

                        SKILL: bounded-platform-navigation
                        - use the fewest direct lookups needed for the current step, then stop
                        - Plane: if the mission project UUID is unknown, list projects once with sparse id/name/identifier fields, select the mission match, then reuse that UUID
                        - Gitea: software-team mission repositories live under owner agent-workspace; if owner/repo is unknown, search_repos once with the mission anchor and select only matching agent-workspace results
                        - for a known Gitea PR or file, read that exact PR/path; for repository-root discovery use get_repository_tree rather than guessing a directory path such as '.'
                        - Mattermost: if the channel id is unknown, list_teams once, use the returned team id, then resolve the mission channel by name with get_channel_by_name; use list_my_channels only when the mission channel name is genuinely unknown
                        - for a known Mattermost channel use its channel id and recent messages/thread; use team-wide search only when the location is genuinely unknown
                        - never invent internal hosts, owner/repo values, team/channel ids, or other locators when a resolver tool can return the authoritative value
                        - do not read entire repositories, projects, wikis, or channels

                        SKILL: capability-aware-recovery
                        - use only capabilities actually present in this profile
                        - on a schema/validation error, correct the arguments once; on 404, resolve the exact locator once; on response-shape failure, switch to a sparse/supported representation
                        - do not repeat an equivalent failed call without new evidence
                        - distinguish verified observations from assumptions
                        """)
                .defaultArgument(PlaneMcpServer.ID, "list_projects", "fields", "id,name,identifier")
                .defaultArgument(PlaneMcpServer.ID, "list_work_items", "fields", "id,name,sequence_id,state,priority,assignees")
                .defaultArgument(PlaneMcpServer.ID, "list_work_items", "per_page", 12)
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
