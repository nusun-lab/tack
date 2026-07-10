package us.nusun.llmshare.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.framework.inventory.ToolProfileProvider;
import us.nusun.llmshare.framework.model.ToolProfile;

import java.util.List;

@ApplicationScoped
public class SourceControlToolProfile implements ToolProfileProvider {
    public static final String ID = "source-control";

    private final GiteaMcpServer gitea;
    private final int maxResultTokens;

    @Inject
    public SourceControlToolProfile(
            GiteaMcpServer gitea,
            @ConfigProperty(name = "agent.tool.profile.source-control.max-result-tokens", defaultValue = "6000") int maxResultTokens) {
        this.gitea = gitea;
        this.maxResultTokens = maxResultTokens;
    }

    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder(ID)
                .mcpServer(gitea.definition())
                .promptGuidance("""
                                 - read before writing
                                 - Gitea is for source control and implementation evidence
                                 - Plane owns planned work, assignments, status, blockers, dependencies, priorities, and delivery planning
                                 - Gitea issues, labels, milestones, and time tracking must never be used for project coordination
                                 - always create repositories in the agent-workspace organization
                                 - avoid duplicate repositories, branches, pull requests, tags, or releases
                                 - do not use native wiki features
                                 - canonical documentation should live in its own markdown repository
                                 - documentation should be well-linked so it is possible to navigate to any page starting from the index (readme.md)
                                 - do not read entire documentation repositories, try to read only what you need
                                 - generally reference the url attribute of repositories, not clone or ssh
                                 """)
                .denyTools(GiteaMcpServer.ID,
                        "delete_repository",
                        "delete_branch",
                        "wiki_read",
                        "wiki_write",
                        "delete_file",
                        "delete_release",
                        "delete_tag",
                        "list_issues",
                        "search_issues",
                        "issue_read",
                        "issue_write",
                        "label_read",
                        "label_write",
                        "milestone_read",
                        "milestone_write",
                        "timetracking_read",
                        "timetracking_write",
                        "actions_config_write",
                        "actions_run_write",
                        "notification_read",
                        "notification_write",
                        "package_read",
                        "package_write",
                        "fork_repo",
                        "search_org_teams",
                        "search_users",
                        "get_gitea_mcp_server_version")
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
