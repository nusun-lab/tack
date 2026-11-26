package us.nusun.tack.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import us.nusun.tack.framework.tool.ToolProfileProvider;
import us.nusun.tack.framework.tool.ToolProfile;

import java.util.List;

@ApplicationScoped
public class IntegrationToolProfile implements ToolProfileProvider {
    public static final String ID = "integration";

    private final KaneoMcpServer kaneo;
    private final GiteaMcpServer gitea;

    @Inject
    public IntegrationToolProfile(
            KaneoMcpServer kaneo,
            GiteaMcpServer gitea) {
        this.kaneo = kaneo;
        this.gitea = gitea;
    }

    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder(ID)
                .mcpServer(kaneo.definition())
                .mcpServer(gitea.definition())
                .allowTools(KaneoMcpServer.ID,
                        "whoami",
                        "list_workspaces",
                        "list_projects",
                        "list_tasks",
                        "get_task",
                        "get_task_by_ticket_id",
                        "list_task_comments")
                .allowTools(GiteaMcpServer.ID,
                        "search_repos",
                        "list_pull_requests",
                        "pull_request_read",
                        "pull_request_write",
                        "get_commit")
                .promptGuidance(KaneoWorkGuidance.PROJECT + KaneoWorkGuidance.TASKS + RepositoryWritingGuidance.DISCOVERY + """
                        - Use the exact PR and linked Kaneo project/ticket. The calling routine owns candidate selection.
                        - Before each merge, fresh-read the PR head, base, mergeability, conflicts, and relevant validation.
                          Use pull_request_write only for merges. Stop if Gitea already shows the PR closed or merged.
                        """)
                .build());
    }
}
