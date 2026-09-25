package us.nusun.llmshare.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.framework.inventory.ToolProfileProvider;
import us.nusun.llmshare.framework.model.ToolProfile;

import java.util.List;

@ApplicationScoped
public class SourceControlWriteToolProfile implements ToolProfileProvider {
    public static final String ID = "source-control-write";
    public static final String SANDBOX_SERVER = "sandbox";
    public static final String SANDBOX_EXEC = "sandbox_exec";

    private final GiteaMcpServer gitea;
    private final int maxResultTokens;

    @Inject
    public SourceControlWriteToolProfile(
            GiteaMcpServer gitea,
            @ConfigProperty(name = "agent.tool.profile.source-control.max-result-tokens", defaultValue = "6000") int maxResultTokens) {
        this.gitea = gitea;
        this.maxResultTokens = maxResultTokens;
    }

    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder(ID)
                .mcpServer(gitea.definition())
                .allowTool(SANDBOX_SERVER, SANDBOX_EXEC)
                .promptGuidance("""
                                 - the canonical Gitea web/clone base is https://gitea.apps.cluster.lab.nusun.us; never substitute github.com or invent another host when constructing repository, commit, branch, or pull-request links
                                 - use the persistent persona sandbox as the repository execution environment for repository content changes
                                 - keep checkouts under /sandbox/repos and use ordinary git plus local filesystem/build/test commands for repository contents, edits, diff/status, validation, commits, and pushes
                                 - reuse an existing checkout when present; clone only when absent, and prefer the canonical HTTPS URL under https://gitea.apps.cluster.lab.nusun.us already present in mission/project context over internal service URLs returned by metadata APIs
                                 - an existing but empty source repository is a valid implementation starting point; the first assigned Developer may initialize the repository, establish a minimal baseline/default branch when needed, then create/use the task branch and continue normal delivery
                                 - do not require PM to seed main, announce the assignment in chat, or pre-confirm a Gitea collaborator grant before attempting assigned work
                                 - do not invent permission blockers: attempt the normal clone/push/PR workflow; only an actual authentication/authorization failure is evidence of a source-access blocker
                                 - do not mutate repository files through Gitea file-content APIs; use the sandbox working tree and ordinary git
                                 - Gitea remains appropriate for repository discovery/provisioning, branch/commit/PR metadata, review evidence, tags/releases, and remote verification
                                 - CI/workflow execution is not enabled in this Gitea environment; validate changes directly in the sandbox and use that validation as delivery evidence
                                 - do not claim source changes, validation, commits, or pushes without direct sandbox or remote evidence
                                 - make one focused repository change per step unless the act prompt says otherwise
                                 - Plane owns planned work, assignments, status, blockers, dependencies, priorities, and delivery planning
                                 - Gitea issues, labels, milestones, and time tracking must never be used for project coordination
                                 - always create repositories in the agent-workspace organization
                                 - avoid duplicate repositories, branches, pull requests, tags, or releases
                                 - do not use native wiki features
                                 - canonical documentation should describe the product, not the daily work log
                                 - documentation should be navigable from the index (readme.md)
                                 - keep individual markdown files compact, usually under 200-300 lines; split or summarize before appending long logs

                                 SKILL: mission-anchor-reuse
                                 - once owner/repo, checkout path, branch, commit, PR number, or exact file path is verified, reuse it directly; do not rediscover it through broad repository listings

                                 SKILL: bounded-platform-navigation
                                 - known PR/file -> exact lookup; repository-root discovery -> get_repository_tree, not a guessed '.' path
                                 - stop reading once the edit/validation target is clear, then use the sandbox for implementation

                                 SKILL: capability-aware-recovery
                                 - if a git/source operation fails, classify the failure before retrying; correct an argument/locator once and do not repeat an equivalent failed command or API call without new evidence
                                 - reuse the same task branch and PR for recovery when possible rather than manufacturing replacements
                                 - on an empty remote, initialize once and proceed; on an actual 401/403 or rejected push, record that concrete failure rather than requiring speculative permission verification beforehand
                                 """)
                .denyTools(GiteaMcpServer.ID,
                        "delete_repository",
                        "delete_branch",
                        "wiki_read",
                        "wiki_write",
                        "delete_file",
                        "delete_release",
                        "delete_tag",
                        "list_issues",
                        "search_issues",
                        "issue_read",
                        "issue_write",
                        "label_read",
                        "label_write",
                        "milestone_read",
                        "milestone_write",
                        "timetracking_read",
                        "timetracking_write",
                        "actions_run_read",
                        "actions_config_write",
                        "actions_run_write",
                        "notification_read",
                        "notification_write",
                        "package_read",
                        "package_write",
                        "fork_repo",
                        "search_org_teams",
                        "search_users",
                        "get_gitea_mcp_server_version")
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
