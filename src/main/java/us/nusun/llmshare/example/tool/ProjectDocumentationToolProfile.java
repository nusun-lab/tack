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
                        - this profile is only for Project Manager documentation in the Architect-established system repository
                        - read current system documentation before writing; docs/architecture.md remains Architect-owned
                        - PM owns docs/project-basics.md as compact canonical mission navigation and stable operating basics
                        - project basics may link the mission, Plane project, shared Mattermost channel, system/component
                          repository map, role responsibilities, and stable delivery conventions
                        - do not copy transient backlog/status/WIP into documentation; Plane remains authoritative for work state
                        - do not edit component repositories, application source, GitOps, architecture decisions, or system tests
                        - do not provision repositories or initialize missing repository architecture; report missing system repo to ARCH
                        - all software-team mission repositories live under Gitea owner agent-workspace
                        - use the persistent persona sandbox under /sandbox/repos for documentation changes; first reuse /sandbox/repos/<repo> when it is already a Git checkout
                        - when a checkout is absent and the exact clone URL is not already verified, call search_repos with the mission/repository anchor, select the matching agent-workspace repository, and use its returned clone_url exactly
                        - never guess gitea.local, Kubernetes service hosts, alternate domains, or reconstructed clone endpoints; the canonical external Gitea host is https://gitea.apps.cluster.lab.nusun.us
                        - reuse an existing documentation branch/PR whenever possible and use pull_request_write only to create/update it
                        - final integration belongs to Architect
                        - validate the resulting markdown/diff directly and never claim a write without evidence
                        """)
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
