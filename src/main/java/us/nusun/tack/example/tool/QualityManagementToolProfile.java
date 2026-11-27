package us.nusun.tack.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import us.nusun.tack.framework.tool.ToolProfileProvider;
import us.nusun.tack.framework.tool.ToolProfile;

import java.util.List;

@ApplicationScoped
public class QualityManagementToolProfile implements ToolProfileProvider {
    public static final String ID = "quality-management";

    private final KaneoMcpServer kaneo;
    private final GiteaMcpServer gitea;

    @Inject
    public QualityManagementToolProfile(
            KaneoMcpServer kaneo, GiteaMcpServer gitea) {
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
                        "list_workspace_members",
                        "list_tasks",
                        "get_task",
                        "get_task_relations",
                        "list_task_comments",
                        "get_task_by_ticket_id",
                        "create_task",
                        "create_label",
                        "update_task",
                        "update_task_status",
                        "update_task_assignee",
                        "create_task_relation",
                        "create_task_comment")
                .allowTools(GiteaMcpServer.ID, "search_repos", "list_pull_requests", "pull_request_read")
                .promptGuidance(KaneoWorkGuidance.PROJECT + KaneoWorkGuidance.TASKS + KaneoWorkGuidance.LIFECYCLE + KaneoWorkGuidance.CLASSIFICATION)
                .build());
    }
}
