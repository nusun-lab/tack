package us.nusun.llmshare.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.framework.inventory.ToolProfileProvider;
import us.nusun.llmshare.framework.model.ToolProfile;

import java.util.List;

@ApplicationScoped
public class ProductOwnershipToolProfile implements ToolProfileProvider {
    public static final String ID = "product-ownership";

    private final PlaneMcpServer plane;
    private final int maxResultTokens;

    @Inject
    public ProductOwnershipToolProfile(
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
                        "create_work_item",
                        "update_work_item",
                        "manage_work_item_label",
                        "list_work_item_activities",
                        "list_work_item_comments",
                        "retrieve_work_item_comment",
                        "create_work_item_comment",
                        "update_work_item_comment",
                        "list_labels",
                        "create_label",
                        "list_states")
                .promptGuidance("""
                        - this profile creates and updates Plane product work; the calling routine owns product policy,
                          lifecycle decisions, acceptance criteria, prioritization decisions, and WIP policy
                        - Tack work-item kinds are represented by exactly one attached type:* label, not native Plane types
                        - create a missing classification label only when the routine explicitly requires that kind, then
                          attach it explicitly with manage_work_item_label
                        - before mutating an existing item based on kind, verify its attached labels
                        - use update_work_item with the complete assignees array for assignment changes
                        - comments preserve evidence/context and are not lifecycle states

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
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
