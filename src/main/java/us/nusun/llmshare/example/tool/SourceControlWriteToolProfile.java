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
            @ConfigProperty(name = "agent.tool.profile.source-control.max-result-tokens", defaultValue = "6000")
            int maxResultTokens) {
        this.gitea = gitea;
        this.maxResultTokens = maxResultTokens;
    }

    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder(ID)
                .mcpServer(gitea.definition())
                .allowTool(SANDBOX_SERVER, SANDBOX_EXEC)
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
                        "pull_request_read",
                        "pull_request_write")
                .promptGuidance("""
                        - this profile changes source in an existing repository selected by the calling routine; it cannot
                          provision repositories
                        - software-team mission repositories live under Gitea owner agent-workspace
                        - use the persistent persona sandbox under /sandbox/repos for repository content changes
                        - use ordinary git/filesystem/build/test commands for edits, validation, commits, and pushes
                        - reuse an existing checkout/branch/PR whenever possible; clone only when absent
                        - when clone_url is unknown, resolve the exact repository with search_repos and use the returned
                          agent-workspace clone_url; never reconstruct alternate/internal remotes
                        - canonical external Gitea is https://gitea.apps.cluster.lab.nusun.us
                        - use pull_request_write for PR create/update work only; merge through the integration profile
                        - only an actual auth/write failure establishes a write-permission blocker
                        - validate directly in the sandbox; Gitea Actions/CI are not enabled
                        - never claim source changes, validation, commits, or pushes without direct evidence
                        - reuse exact owner/repo, branch, commit, PR, and file anchors
                        """)
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
