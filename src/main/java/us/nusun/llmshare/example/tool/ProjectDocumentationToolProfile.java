package us.nusun.llmshare.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.framework.inventory.ToolProfileProvider;
import us.nusun.llmshare.framework.model.ToolProfile;

import java.util.List;

@ApplicationScoped
public class ProjectDocumentationToolProfile implements ToolProfileProvider {
    public static final String ID = "project-documentation";
    public static final String SANDBOX_SERVER = "sandbox";
    public static final String SANDBOX_EXEC = "sandbox_exec";

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
                        - this profile changes docs/project-basics.md in the existing system repository selected by the
                          calling routine; it is not a general source/architecture editing profile
                        - read the current file and directly relevant system documentation before writing
                        - do not provision repositories or edit component source, GitOps, architecture decisions, or tests
                        - software-team mission repositories live under Gitea owner agent-workspace
                        - use the persistent persona sandbox under /sandbox/repos and reuse an existing checkout/branch/PR
                        - when clone_url is unknown, resolve the exact system repository with search_repos and use the
                          returned agent-workspace clone_url; never reconstruct alternate/internal remotes
                        - canonical external Gitea is https://gitea.apps.cluster.lab.nusun.us
                        - use pull_request_write for documentation PR create/update work only; merge through integration
                        - validate the resulting markdown/diff directly and never claim a write without evidence
                        """)
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
