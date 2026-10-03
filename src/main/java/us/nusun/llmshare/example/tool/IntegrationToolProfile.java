package us.nusun.llmshare.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.framework.inventory.ToolProfileProvider;
import us.nusun.llmshare.framework.model.ToolProfile;

import java.util.List;

@ApplicationScoped
public class IntegrationToolProfile implements ToolProfileProvider {
    public static final String ID = "integration";

    private final PlaneMcpServer plane;
    private final GiteaMcpServer gitea;
    private final int maxResultTokens;

    @Inject
    public IntegrationToolProfile(
            PlaneMcpServer plane,
            GiteaMcpServer gitea,
            @ConfigProperty(name = "agent.tool.profile.source-control.max-result-tokens", defaultValue = "6000") int maxResultTokens) {
        this.plane = plane;
        this.gitea = gitea;
        this.maxResultTokens = maxResultTokens;
    }

    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder(ID)
                .mcpServer(plane.definition())
                .mcpServer(gitea.definition())
                .allowTools(PlaneMcpServer.ID,
                        "retrieve_work_item",
                        "retrieve_work_item_by_identifier",
                        "list_work_item_comments",
                        "retrieve_work_item_comment")
                .allowTools(GiteaMcpServer.ID,
                        "search_repos",
                        "list_pull_requests",
                        "pull_request_read",
                        "pull_request_write",
                        "get_commit")
                .promptGuidance("""
                        - Plane lifecycle state and task context identify the implementation unit; comments provide evidence/context, not hidden approval states
                        - an In Progress work:implementation Story, work:defect Bug, or work:test Task with an OPEN pull request is eligible for a fresh integration check regardless of PR author; do not require REVIEW READY, INTEGRATION READY, or another magic approval phrase
                        - Gitea is authoritative for current pull-request/source state; the canonical Gitea web/clone base is https://gitea.apps.cluster.lab.nusun.us
                        - integration is batch work: list current open PRs in known mission repositories, process oldest safe candidates first, and merge every independently safe PR in the same act until no immediately safe candidate remains or the tool budget is reached
                        - immediately before each merge, fresh-read only the exact work item/PR evidence needed for safety: repository, PR, intended base, current head/base, mergeability/conflicts, validation, acceptance scope, and any current blocker/review concern
                        - after merging one PR, fresh-read any later candidate targeting that same repository because its base changed
                        - a blocked, conflicted, stale, or ambiguous PR never blocks independent safe PRs behind it
                        - use pull_request_write only for merge operations in this profile
                        - merge only when current PR evidence is safe and consistent with the source work unit's scope; work:test changes belong in the system repository and must preserve linked Epic acceptance intent; if ambiguous, stale, conflicted, failing validation, or product intent is unresolved, skip that PR and continue with independent candidates
                        - do not manufacture an intermediate Plane comment state to authorize or reject integration

                        SKILL: mission-anchor-reuse
                        - reuse the exact verified Plane work-item UUID/identifier, Gitea owner/repo, PR number, head, and
                          base; never shorten/reconstruct UUIDs or rediscover a known PR through broad lists
                        - software-team mission repositories are under owner agent-workspace; if an exact stored locator
                          unexpectedly fails or owner is genuinely absent from current thought, use search_repos once for
                          the exact repository name and adopt only the returned canonical owner/repo; never guess owners

                        SKILL: plane-query-recipes
                        - For linked work classification, use retrieve_work_item(project_id=<verified>,
                          work_item_id=<selected UUID>, expand="labels",
                          fields="id,name,sequence_id,state,labels"). Never include assignees or omit fields.
                        - For description/acceptance context, use a separate retrieve_work_item with
                          fields="id,name,sequence_id,state,description_html". Never combine ownership and labels.
                        - A WorkItemDetail response-shape error ends that Plane retrieval shape for the batch; do not retry
                          it against the next PR's item.

                        SKILL: bounded-platform-navigation
                        - retrieve only the Plane items/comments and exact PR/commits required for the current batch; a safe batch may exceed normal 1-3-call guidance and should stop only when no immediately safe candidate remains or the execution budget is reached

                        SKILL: capability-aware-recovery
                        - on 404, resolve the exact locator once; on schema/validation error, correct arguments once; if the PR is already closed/merged or the operation is unsupported, stop rather than retrying equivalent calls
                        """)
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
