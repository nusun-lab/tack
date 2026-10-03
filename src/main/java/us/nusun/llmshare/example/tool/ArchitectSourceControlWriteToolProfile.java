package us.nusun.llmshare.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.framework.inventory.ToolProfileProvider;
import us.nusun.llmshare.framework.model.ToolProfile;

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
                        - Architect is the only software-team role that provisions mission repositories
                        - always provision in agent-workspace and avoid duplicate/near-duplicate repositories
                        - own exactly one system repository plus component repositories justified by architecture
                        - system repository owns gitops/, docs/, and tests/system/
                        - component repositories contain independently deployable application components
                        - prefer <anchor>-service for a deployable component that implements/exposes APIs
                        - reserve <anchor>-api for API specification/contract repositories such as OpenAPI or AsyncAPI
                        - use the persistent persona sandbox under /sandbox/repos for content changes and conflict recovery
                        - for an existing repository checkout, resolve it with search_repos when needed and use the returned agent-workspace clone_url exactly; never probe alternate/internal Gitea hosts
                        - initialize every new repository with a small meaningful baseline commit on main before creating
                          any feature branch or pull request; the baseline may be a README or required scaffold
                        - verify main exists as the intended default branch before feature work begins; never allow a
                          feature branch to become the first/only branch because Gitea may make that branch the default
                        - reuse existing branches/PRs and resolve conflicts in place rather than manufacturing replacements
                        - use the canonical Gitea base https://gitea.apps.cluster.lab.nusun.us
                        - use pull_request_write here for Architect PR create/update/update-branch work; final
                          merge belongs to the integration profile
                        - validate directly in the sandbox; Gitea Actions/CI are not enabled in this environment
                        - never claim source mutations or validation without direct evidence
                        - do not use Gitea issues, labels, milestones, wiki, releases, packages, or time tracking
                        """)
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
