package us.nusun.tack.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.tack.framework.tool.ToolProfileProvider;
import us.nusun.tack.framework.tool.ToolProfile;

import java.util.List;

@ApplicationScoped
public class SourceControlWriteToolProfile implements ToolProfileProvider {
    public static final String ID = "source-control-write";

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
                .allowTool("sandbox", "sandbox_exec")
                .allowTools(GiteaMcpServer.ID,
                        "get_me", "search_repos", "get_repository_tree", "get_dir_contents", "get_file_contents",
                        "list_branches", "list_commits", "get_commit",
                        "list_pull_requests", "pull_request_read", "pull_request_write")
                .promptGuidance("""
                        - this profile changes source in an existing repository selected by the calling routine; it cannot
                          provision repositories
                        """ + RepositoryWritingGuidance.REPOSITORY_GUIDANCE + """
                        - use ordinary git/filesystem/build/test commands for edits, validation, commits, and pushes
                        - only an actual auth/write failure establishes a write-permission blocker
                        - validate directly in the sandbox; Gitea Actions/CI are not enabled
                        - never claim source changes, validation, commits, or pushes without direct evidence
                        - reuse exact owner/repo, branch, commit, PR, and file anchors
                        """)
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
