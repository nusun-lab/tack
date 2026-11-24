package us.nusun.tack.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.tack.framework.inventory.ToolProfileProvider;
import us.nusun.tack.framework.model.ToolProfile;

import java.util.List;

@ApplicationScoped
public class ArchitectSourceControlWriteToolProfile implements ToolProfileProvider {
    public static final String ID = "architect-source-control-write";
    public static final String SANDBOX_SERVER = "sandbox";
    public static final String SANDBOX_EXEC = "sandbox_exec";

    private final GiteaMcpServer gitea;
    private final int maxResultTokens;

    @Inject
    public ArchitectSourceControlWriteToolProfile(
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
                        "list_my_repos",
                        "list_org_repos",
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
                .promptGuidance("""
                        - this profile can provision repositories and change source; the calling Architect routine owns
                          repository topology, naming, architecture policy, and the work being implemented
                        - provision software-team mission repositories under agent-workspace and avoid verified duplicates
                        - use the persistent persona sandbox under /sandbox/repos for content changes and conflict recovery
                        - for an existing repository, resolve it with search_repos when needed and use the returned
                          agent-workspace clone_url; never reconstruct alternate/internal remotes
                        - initialize a newly created repository with a small meaningful baseline commit on main before
                          feature-branch/PR work so main becomes the intended initial branch
                        - reuse existing branches/PRs and resolve conflicts in place when possible
                        - canonical external Gitea is https://gitea.apps.cluster.lab.nusun.us
                        - use pull_request_write here for PR create/update work; perform final merges through integration
                        - validate directly in the sandbox; Gitea Actions/CI are not enabled
                        - never claim repository/source mutations or validation without direct evidence
                        """)
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
