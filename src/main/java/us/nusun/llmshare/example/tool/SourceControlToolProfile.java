package us.nusun.llmshare.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.framework.inventory.ToolProfileProvider;
import us.nusun.llmshare.framework.model.ToolProfile;

import java.util.List;

@ApplicationScoped
public class SourceControlToolProfile implements ToolProfileProvider {
    public static final String ID = "source-control";

    private final GiteaMcpServer gitea;
    private final int maxResultTokens;

    @Inject
    public SourceControlToolProfile(
            GiteaMcpServer gitea,
            @ConfigProperty(name = "agent.tool.profile.source-control.max-result-tokens", defaultValue = "6000") int maxResultTokens) {
        this.gitea = gitea;
        this.maxResultTokens = maxResultTokens;
    }

    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder(ID)
                .mcpServer(gitea.definition())
                .promptGuidance("""
                                 - the canonical Gitea web/clone base is https://gitea.apps.cluster.lab.nusun.us; never substitute github.com or invent another host when constructing repository, commit, branch, or pull-request links
                                 - read only the direct files or links needed for the current step before writing
                                 - make one focused source-control change per step unless the act prompt says otherwise
                                 - when sandbox_exec is available, use the persistent persona sandbox as the repository execution environment
                                 - in that sandbox, keep checkouts under /sandbox/repos and use ordinary git plus local filesystem/build/test commands for repository contents, edits, diff/status, validation, commits, and pushes
                                 - reuse an existing checkout when present; clone only when absent, and prefer the canonical HTTPS URL under https://gitea.apps.cluster.lab.nusun.us already present in mission/project context over internal service URLs returned by metadata APIs
                                 - when sandbox_exec is available, do not mutate repository files through Gitea file-content APIs; Gitea remains appropriate for repository discovery/provisioning, branch/commit/PR metadata, review evidence, tags/releases, and remote verification
                                 - CI/workflow execution is not enabled in this Gitea environment; do not create, inspect, wait for, or rely on Actions/workflows or CI checks. Validate changes directly in the sandbox and use that validation as delivery evidence
                                 - do not claim source changes, validation, commits, or pushes without direct sandbox or remote evidence
                                 - Gitea is for source-control metadata and implementation evidence
                                 - Plane owns planned work, assignments, status, blockers, dependencies, priorities, and delivery planning
                                 - Gitea issues, labels, milestones, and time tracking must never be used for project coordination
                                 - always create repositories in the agent-workspace organization
                                 - avoid duplicate repositories, branches, pull requests, tags, or releases
                                 - do not use native wiki features
                                 - canonical documentation should describe the product, not the daily work log
                                 - documentation should be navigable from the index (readme.md)
                                 - keep individual markdown files compact, usually under 200-300 lines; split or summarize before appending long logs
                                 - small navigation and organization improvements are valuable documentation work
                                 - do not read entire documentation repositories

                                 SKILL: mission-anchor-reuse
                                 - once owner/repo, branch, commit, PR number, or exact path is verified, reuse it directly; do not relist repositories or branches to rediscover it unless direct lookup fails

                                 SKILL: bounded-platform-navigation
                                 - known PR -> pull_request_read; known file -> exact get_file_contents; repository root -> get_repository_tree rather than guessing '.' with get_dir_contents
                                 - stop once the current source question has enough evidence

                                 SKILL: capability-aware-recovery
                                 - use only tools actually present in this profile; do not plan around sandbox_exec unless it is available for the current act
                                 - validation/schema error -> correct arguments once; 404 -> resolve exact locator once; unsupported operation -> use a supported alternative
                                 - do not repeat an equivalent failed call without new evidence
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
