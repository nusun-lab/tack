package us.nusun.tack.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import us.nusun.tack.framework.tool.ToolProfileProvider;
import us.nusun.tack.framework.tool.ToolProfile;

import java.util.List;

@ApplicationScoped
public class ArchitectSourceControlWriteToolProfile implements ToolProfileProvider {
    public static final String ID = "architect-source-control-write";
    public static final String SANDBOX_SERVER = "sandbox";
    public static final String SANDBOX_EXEC = "sandbox_exec";

    private final GiteaMcpServer gitea;

    @Inject
    public ArchitectSourceControlWriteToolProfile(
            GiteaMcpServer gitea) {
        this.gitea = gitea;
    }

    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder(ID)
                .mcpServer(gitea.definition())
                .allowTool(SANDBOX_SERVER, SANDBOX_EXEC)
                .allowTools(GiteaMcpServer.ID,
                        "get_me",
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
                        "pull_request_write")
                .promptGuidance(RepositoryWritingGuidance.REPOSITORY_GUIDANCE + """
                        Search for the exact mission repositories before creating any. Reuse existing repositories and
                        repair incomplete baselines. Initialize a missing main baseline in the sandbox.
                        """)
                .build());
    }
}
