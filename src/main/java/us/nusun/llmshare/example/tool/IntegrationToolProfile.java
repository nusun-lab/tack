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
                        "pull_request_read",
                        "pull_request_write",
                        "get_commit")
                .promptGuidance("""
                        - Plane lifecycle state and task context identify the implementation unit; comments provide evidence/context, not hidden approval states
                        - an In Progress work:implementation Story, work:defect Bug, or work:test Task with an exact linked OPEN pull request is eligible for a fresh integration check; do not require REVIEW READY, INTEGRATION READY, or another magic approval phrase
                        - Gitea is authoritative for current pull-request/source state; the canonical Gitea web/clone base is https://gitea.apps.cluster.lab.nusun.us
                        - immediately before merge, verify only the exact selected work item/PR evidence needed for safety: repository, PR, intended base, current head/base, mergeability/conflicts, validation, acceptance scope, and any current blocker/review concern
                        - use pull_request_write only for the final merge operation in this profile
                        - merge only when the current PR evidence is safe and consistent with the selected source work unit's scope; work:test changes belong in the system repository and must preserve linked Epic acceptance intent; if ambiguous, stale, conflicted, failing validation, or product intent is unresolved, make no source-control mutation
                        - do not manufacture an intermediate Plane comment state to authorize or reject integration

                        SKILL: mission-anchor-reuse
                        - reuse the exact verified Plane work-item UUID/identifier, Gitea owner/repo, PR number, head, and base; never shorten/reconstruct UUIDs or rediscover a known PR through broad lists

                        SKILL: bounded-platform-navigation
                        - retrieve only the selected Plane item/comments and exact PR/commits required for the merge decision; stop when the decision has enough evidence

                        SKILL: capability-aware-recovery
                        - on 404, resolve the exact locator once; on schema/validation error, correct arguments once; if the PR is already closed/merged or the operation is unsupported, stop rather than retrying equivalent calls
                        """)
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
