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
                        "list_states")
                .promptGuidance("""
                        - this profile updates already-created engineering work; the calling routine owns selection,
                          lifecycle policy, and completion criteria
                        - before any lifecycle mutation, verify the selected item's attached labels and current assignees
                        - never infer classification or ownership from title, wording, comments, or surrounding context
                        - comments are evidence/context, not hidden lifecycle states
                        - this profile cannot create work items, labels, projects, test tasks, or defects

                        SKILL: plane-work-classification
                        - Tack work classification is represented by attached labels epic, story, test, or defect
                        - these labels are unrelated to Plane's native work-item type and type_id; never use type_id to
                          determine Tack classification

                        SKILL: plane-query-recipes
                        - Discovery: list_work_items(project_id=<verified>,
                          fields="id,name,sequence_id,state,priority,assignees")
                        - Classification: retrieve_work_item(project_id=<verified>, work_item_id=<selected UUID>,
                          expand="labels", fields="id,name,sequence_id,state,labels")
                        - Description: retrieve_work_item(project_id=<verified>, work_item_id=<selected UUID>,
                          fields="id,name,sequence_id,state,description_html")
                        - Parent: retrieve_work_item(project_id=<verified>, work_item_id=<selected UUID>,
                          fields="id,name,sequence_id,state,parent"); never expand parent
                        - keep ownership and expanded-label classification in separate WorkItemDetail calls
                        - a field omitted by a sparse projection is unknown, never negative evidence
                        - after a WorkItemDetail response-shape failure, do not retry an equivalent failing shape
                        """)
                .defaultArgument(PlaneMcpServer.ID, "list_projects", "fields", "id,name,identifier")
                .defaultArgument(
                        PlaneMcpServer.ID,
                        "list_work_items",
                        "fields",
                        "id,name,sequence_id,state,priority,assignees")
                .defaultArgument(PlaneMcpServer.ID, "list_work_items", "per_page", 12)
                .hideArgument(PlaneMcpServer.ID, "list_work_items", "pql")
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
