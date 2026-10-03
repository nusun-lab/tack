package us.nusun.llmshare.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.framework.inventory.ToolProfileProvider;
import us.nusun.llmshare.framework.model.ToolProfile;

import java.util.List;

@ApplicationScoped
public class EngineeringWorkToolProfile implements ToolProfileProvider {
    public static final String ID = "engineering-work";

    private final PlaneMcpServer plane;
    private final int maxResultTokens;

    @Inject
    public EngineeringWorkToolProfile(
            PlaneMcpServer plane,
            @ConfigProperty(name = "agent.tool.profile.project-management.max-result-tokens", defaultValue = "5000")
            int maxResultTokens) {
        this.plane = plane;
        this.maxResultTokens = maxResultTokens;
    }

    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder(ID)
                .mcpServer(plane.definition())
                .allowTools(PlaneMcpServer.ID,
                        "get_me",
                        "list_projects",
                        "retrieve_project",
                        "list_work_items",
                        "retrieve_work_item",
                        "retrieve_work_item_by_identifier",
                        "search_work_items",
                        "update_work_item",
                        "list_work_item_activities",
                        "list_work_item_comments",
                        "retrieve_work_item_comment",
                        "create_work_item_comment",
                        "update_work_item_comment",
                        "list_labels",
                        "list_states",
                        "list_work_item_types",
                        "retrieve_work_item_type")
                .promptGuidance("""
                        - this profile is for execution of already-created engineering work, not backlog design
                        - do not create work items, types, labels, projects, epics, test tasks, or defects
                        - work:implementation is a Story child of an Epic; DEV or ARCH may claim assigned Todo -> In Progress
                        - work:defect is a Bug child of an Epic; DEV or ARCH may claim it only after PM assigns it Todo
                        - never change product priority or Epic lifecycle
                        - work:implementation remains In Progress while its source PR is open; after verifying that PR merged,
                          the assigned engineer owns the Story transition to Done
                        - work:defect remains In Progress through fix merge; QA retests and closes the Bug
                        - comments contain evidence/blockers only and never act as hidden workflow states
                        - retrieve the selected work item before execution when parent or rich context is required
                        """)
                .defaultArgument(PlaneMcpServer.ID, "list_projects", "fields", "id,name,identifier")
                .defaultArgument(
                        PlaneMcpServer.ID,
                        "list_work_items",
                        "fields",
                        "id,name,sequence_id,state,priority,assignees,labels,type_id")
                .defaultArgument(PlaneMcpServer.ID, "list_work_items", "per_page", 12)
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
