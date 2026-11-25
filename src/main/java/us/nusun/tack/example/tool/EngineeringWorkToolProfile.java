package us.nusun.tack.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import us.nusun.tack.framework.tool.ToolProfileProvider;
import us.nusun.tack.framework.tool.ToolProfile;

import java.util.List;

@ApplicationScoped
public class EngineeringWorkToolProfile implements ToolProfileProvider {
    public static final String ID = "engineering-work";

    private final KaneoMcpServer kaneo;

    @Inject
    public EngineeringWorkToolProfile(
            KaneoMcpServer kaneo) {
        this.kaneo = kaneo;
    }

    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder(ID)
                .mcpServer(kaneo.definition())
                .allowTools(KaneoMcpServer.ID,
                        "whoami",
                        "list_tasks",
                        "get_task",
                        "get_task_by_ticket_id",
                        "get_task_relations",
                        "list_task_comments",
                        "update_task_status",
                        "create_task_comment")
                .promptGuidance(KaneoWorkGuidance.DISCOVERY + KaneoWorkGuidance.LIFECYCLE)
                .build());
    }
}
