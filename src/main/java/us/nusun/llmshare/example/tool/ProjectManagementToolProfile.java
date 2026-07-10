package us.nusun.llmshare.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.framework.inventory.ToolProfileProvider;
import us.nusun.llmshare.framework.model.ToolProfile;

import java.util.List;

@ApplicationScoped
public class ProjectManagementToolProfile implements ToolProfileProvider {
    public static final String ID = "project-management";

    private final PlaneMcpServer plane;
    private final int maxResultTokens;

    @Inject
    public ProjectManagementToolProfile(
            PlaneMcpServer plane,
            @ConfigProperty(name = "agent.tool.profile.project-management.max-result-tokens", defaultValue = "5000") int maxResultTokens) {
        this.plane = plane;
        this.maxResultTokens = maxResultTokens;
    }

    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder(ID)
                .mcpServer(plane.definition())
                .promptGuidance("""
                        - find existing projects and work items before creating anything
                        - Plane is the sole source of truth for planned work, assignments, status, blockers, dependencies, priorities, and delivery planning
                        - avoid duplicate projects or work items
                        - record blockers plainly with owner and evidence
                        - no single work item should be too long
                        - strive to break large work items down into small tasks
                        - when breaking items into smaller pieces, make sure the parent item summarizes instead of duplicating the content of its children
                        - workspace members are automatically members of public projects
                        - do not attempt to add members to public projects membership is opt-in""")
                .denyTools(PlaneMcpServer.ID,
                        "delete_project",
                        "delete_work_item",
                        "delete_work_item_comment",
                        "delete_work_item_link",
                        "delete_work_log",
                        "delete_cycle",
                        "delete_module",
                        "delete_initiative",
                        "delete_intake_work_item",
                        "delete_label",
                        "delete_work_item_property",
                        "delete_work_item_type",
                        "delete_state",
                        "delete_epic",
                        "delete_milestone",
                        "get_project_worklog_summary",
                        "list_work_logs",
                        "create_work_log",
                        "update_work_log",
                        "list_intake_work_items",
                        "create_intake_work_item",
                        "retrieve_intake_work_item",
                        "update_intake_work_item",
                        "get_project_features",
                        "update_project_features",
                        "get_workspace_features",
                        "update_workspace_features",
                        "list_work_item_properties",
                        "create_work_item_property",
                        "retrieve_work_item_property",
                        "update_work_item_property",
                        "list_work_item_types",
                        "create_work_item_type",
                        "retrieve_work_item_type",
                        "update_work_item_type",
                        "retrieve_workspace_page",
                        "retrieve_project_page",
                        "create_workspace_page",
                        "create_project_page")
                .defaultArgument(PlaneMcpServer.ID, "list_projects", "fields", "id,name,identifier")
                .defaultArgument(PlaneMcpServer.ID, "list_work_items", "fields", "id,name,sequence_id,state,priority,assignees")
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
