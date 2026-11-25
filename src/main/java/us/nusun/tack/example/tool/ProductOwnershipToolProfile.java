package us.nusun.tack.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import us.nusun.tack.framework.tool.ToolProfileProvider;
import us.nusun.tack.framework.tool.ToolProfile;

import java.util.List;

@ApplicationScoped
public class ProductOwnershipToolProfile implements ToolProfileProvider {
    public static final String ID = "product-ownership";

    private final KaneoMcpServer kaneo;

    @Inject
    public ProductOwnershipToolProfile(
            KaneoMcpServer kaneo) {
        this.kaneo = kaneo;
    }

    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder(ID)
                .mcpServer(kaneo.definition())
                .allowTools(KaneoMcpServer.ID,
                        "whoami",
                        "list_workspace_members",
                        "list_tasks",
                        "get_task",
                        "get_task_relations",
                        "list_task_comments",
                        "create_task",
                        "create_label",
                        "update_task",
                        "update_task_status",
                        "update_task_assignee",
                        "create_task_relation",
                        "create_task_comment")
                .promptGuidance(KaneoWorkGuidance.DISCOVERY + KaneoWorkGuidance.LIFECYCLE + KaneoWorkGuidance.CLASSIFICATION)
                .build());
    }
}
