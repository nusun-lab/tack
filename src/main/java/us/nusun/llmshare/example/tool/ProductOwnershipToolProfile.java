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
                        "list_work_item_activities",
                        "list_work_item_comments",
                        "retrieve_work_item_comment",
                        "create_work_item_comment",
                        "update_work_item_comment",
                        "list_labels",
                        "list_states",
                        "list_work_item_types",
                        "retrieve_work_item_type",
                        "resolve_work_item_type")
                .promptGuidance("""
                        - Product Owner owns product outcomes as Plane work items whose work item type is Epic
                        - resolve the Epic work item type once per project and reuse its exact type id
                        - use parent/child hierarchy for work under an epic; labels never substitute for the epic
                        - own epic definition, priority, lifecycle, acceptance intent, and final Done decision
                        - keep one epic In Progress by default; start a second only when the first is externally blocked
                          and parallel product work is explicitly justified
                        - a small ready backlog is useful, but ready backlog is not active WIP
                        - do not create or decompose implementation stories; PM owns Story delivery topology
                        - do not create QA test tasks; QA owns test-engineering tasks derived from epic acceptance intent
                        - QA-created Bug items labeled work:defect are evidence objects awaiting product triage
                        - for a new defect, Product Owner classifies it by setting the correct Epic parent and product
                          priority before PM routes repair
                        - if the defect proves a previously Done Epic no longer satisfies its accepted behavior, move that
                          Epic back to In Progress before repair routing; Done must reflect current product truth
                        - do not assign the repair owner; PM owns delivery assignment and synchronization
                        - never accept an Epic Done while required child delivery/test work is unfinished, a relevant
                          defect remains open, or QA evidence does not demonstrate the promised behavior
                        - comments preserve product decisions/evidence but never invent workflow states
                        - lifecycle uses only Plane states Backlog, Todo, In Progress, Done, Cancelled

                        SKILL: typed-work-navigation
                        - sparse list_work_items includes type_id but parent may be omitted; select a candidate first,
                          then retrieve that exact item when parent or rich description is needed
                        - do not infer type from title; resolve/list work item types and compare exact type ids
                        - do not infer parentage from naming; retrieve the item when parent is required
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
