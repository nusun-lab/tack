package us.nusun.llmshare.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.framework.inventory.ToolProfileProvider;
import us.nusun.llmshare.framework.model.ToolProfile;

import java.util.List;

@ApplicationScoped
public class QualitySourceControlWriteToolProfile implements ToolProfileProvider {
    public static final String ID = "quality-source-control-write";
    public static final String SANDBOX_SERVER = "sandbox";
    public static final String SANDBOX_EXEC = "sandbox_exec";

    private final GiteaMcpServer gitea;
    private final int maxResultTokens;

    @Inject
    public QualitySourceControlWriteToolProfile(
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
                        - this profile is for QA changes in the Architect-established mission system repository only
                        - QA source changes belong under system behavior-test/supporting evidence scope; do not edit
                          component implementation to make tests pass
                        - the system repository baseline must already exist and own tests/system/, docs/, and gitops/
                        - use the persistent persona sandbox under /sandbox/repos for repository content changes
                        - use ordinary git/filesystem/build/test commands for edits, validation, commits, and pushes
                        - reuse an existing checkout/branch/PR whenever possible; clone only when absent
                        - use the canonical Gitea base https://gitea.apps.cluster.lab.nusun.us
                        - do not provision repositories or initialize missing repository architecture; report that to ARCH
                        - do not preflight hypothetical permission blockers; only an actual auth/write failure proves one
                        - Gitea is for repository/branch/commit/PR metadata; Plane owns QA work lifecycle/evidence
                        - use pull_request_write for QA PR create/update work only; integration actions such as
                          merge belong to Architect
                        - validate directly in the sandbox; Gitea Actions/CI are not enabled in this environment
                        - never claim source changes, validation, commits, or pushes without direct evidence
                        - do not use Gitea issues, labels, milestones, wiki, releases, packages, or time tracking
                        - reuse exact owner/repo, branch, commit, PR, and file anchors
                        """)
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
