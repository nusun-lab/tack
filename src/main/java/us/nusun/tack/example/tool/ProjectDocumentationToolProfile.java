package us.nusun.tack.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.tack.framework.inventory.ToolProfileProvider;
import us.nusun.tack.framework.model.ToolProfile;

import java.util.List;

@ApplicationScoped
public class ProjectDocumentationToolProfile implements ToolProfileProvider {
    public static final String ID = "project-documentation";

    private final GiteaMcpServer gitea;
    private final int maxResultTokens;

    @Inject
    public ProjectDocumentationToolProfile(
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
                        - this profile changes docs/project-basics.md in the existing system repository selected by the
                          calling routine; it is not a general source/architecture editing profile
                        - read the current file and directly relevant system documentation before writing
                        - do not provision repositories or edit component source, GitOps, architecture decisions, or tests
                        """ + RepositoryWritingGuidance.REPOSITORY_GUIDANCE + """
                        - validate the resulting markdown/diff directly and never claim a write without evidence
                        """)
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
