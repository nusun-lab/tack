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
                        - find direct existing projects and work items before creating anything
                        - work on only the project or work item named by the current step
                        - Plane is the sole source of truth for planned work, assignments, status, blockers, dependencies, priorities, and delivery planning
                        - avoid duplicate projects or work items
                        - record blockers plainly with owner and evidence
                        - keep each work item small; split oversized items and move detail into children instead of copying it
                        - when splitting work, make the parent summarize instead of duplicating child content
                        - keep product discovery in Backlog, PM-ready implementation work in To Do, active assigned work in In Progress, and verified outcomes in Done""")
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
                        "create_project_page",
                        "list_pages",
                        "create_page",
                        "retrieve_page",
                        "attach_page_to_work_item",
                        "detach_page_from_work_item",
                        "list_work_item_pages",
                        "get_project_estimate",
                        "list_project_estimate_points",
                        "create_project_estimate",
                        "update_project_estimate",
                        "delete_project_estimate",
                        "link_estimate_to_project",
                        "create_project_estimate_points",
                        "update_project_estimate_point",
                        "delete_project_estimate_point",
                        "manage_work_item_type_properties",
                        "list_work_item_property_options",
                        "retrieve_work_item_property_option",
                        "create_work_item_property_option",
                        "update_work_item_property_option",
                        "delete_work_item_property_option",
                        "get_work_item_property_value",
                        "set_work_item_property_value",
                        "delete_work_item_property_value",
                        "import_work_item_types_to_project",
                        "resolve_work_item_type",
                        "count_work_items",
                        "get_features")
                .defaultArgument(PlaneMcpServer.ID, "list_projects", "fields", "id,name,identifier")
                .defaultArgument(PlaneMcpServer.ID, "list_work_items", "fields", "id,name,sequence_id,state,priority,assignees")
                .defaultArgument(PlaneMcpServer.ID, "list_work_items", "per_page", 50)
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
