package us.nusun.tack.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import us.nusun.tack.framework.tool.ToolProfileProvider;
import us.nusun.tack.framework.tool.ToolProfile;

import java.util.List;

@ApplicationScoped
public class SourceControlToolProfile implements ToolProfileProvider {
    public static final String ID = "source-control";

    private final GiteaMcpServer gitea;

    @Inject
    public SourceControlToolProfile(
            GiteaMcpServer gitea) {
        this.gitea = gitea;
    }

    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder(ID)
                .mcpServer(gitea.definition())
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
                        "list_releases",
                        "get_latest_release",
                        "get_release")
                .promptGuidance(RepositoryWritingGuidance.DISCOVERY + """
                        Read the exact file, tree or PR needed. Gitea Actions are disabled.
                        """)
                .build());
    }
}
