package us.nusun.tack.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.tack.framework.tool.ToolProfileProvider;
import us.nusun.tack.framework.tool.ToolProfile;

import java.util.List;

@ApplicationScoped
public class SourceControlToolProfile implements ToolProfileProvider {
    public static final String ID = "source-control";

    private final GiteaMcpServer gitea;
    private final int maxResultTokens;

    @Inject
    public SourceControlToolProfile(
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
                .allowTools(GiteaMcpServer.ID,
                        "get_me",
                        "list_my_repos",
                        "list_org_repos",
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
                .promptGuidance("""
                        - this profile is strictly read-only source-control context; the calling routine determines what
                          repository/PR evidence is relevant
                        - use the canonical Gitea base https://gitea.apps.cluster.lab.nusun.us
                        - read only direct repository/files/PR evidence needed for the current step
                        - reuse known owner/repo, branch, commit, PR, and path anchors instead of rediscovering them
                        - repository-root discovery uses get_repository_tree; known files use exact get_file_contents
                        - CI/Actions are not enabled in this environment; do not rely on workflow status
                        - do not infer source permission failure without an actual write attempt from a write-capable profile
                        - stop reading once the current source question has enough evidence
                        """)
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
