package us.nusun.tack.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import us.nusun.tack.framework.tool.ToolProfileProvider;
import us.nusun.tack.framework.tool.ToolProfile;

import java.util.List;

@ApplicationScoped
public class QualitySourceControlWriteToolProfile implements ToolProfileProvider {
    public static final String ID = "quality-source-control-write";

    private final GiteaMcpServer gitea;

    @Inject
    public QualitySourceControlWriteToolProfile(
            GiteaMcpServer gitea) {
        this.gitea = gitea;
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
                        - this profile changes QA-owned test/evidence source in the existing system repository selected by
                          the calling routine; it cannot provision repositories
                        - do not use this profile to modify component implementation
                        """ + RepositoryWritingGuidance.REPOSITORY_GUIDANCE + """
                        - use ordinary git/filesystem/build/test commands for edits, validation, commits, and pushes
                        - only an actual auth/write failure establishes a write-permission blocker
                        - validate directly in the sandbox; Gitea Actions/CI are not enabled
                        - never claim source changes, validation, commits, or pushes without direct evidence
                        """)
                .build());
    }
}
