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
                        - Product Owner owns product outcomes as top-level Plane work items labeled type:epic
                        - work-item kind is a Tack convention expressed by exactly one type:* label, not a native Plane type
                        - ensure type:epic exists before creating or classifying product outcomes and attach it explicitly
                        - use parent/child hierarchy for delivery and evidence beneath an epic
                        - own epic definition, priority, lifecycle, acceptance intent, and final Done decision
                        - every type:epic outside Backlog must be assigned to the active Product Owner; assign ownership
                          before or with promotion and preserve it through Todo, In Progress, Done, or Cancelled
                        - every Epic must have concrete externally observable Given/When/Then acceptance before Todo or
                          In Progress; prose may add context but does not replace Gherkin
                        - Gherkin states product behavior/obligations, not implementation mechanics
                        - Backlog Epic means not sufficiently defined; Todo Epic means product/delivery ready for planning;
                          In Progress means downstream delivery is actively consuming it; Done means PO accepted it
                        - maintain a deeper Todo runway while normally allowing no more than three Epics In Progress
                        - promote Todo -> In Progress only when fewer than three Epics are active, PM has prepared reasonable
                          implementation Story coverage, and current downstream capacity can consume the Epic
                        - product readiness alone never activates an Epic; prepare Todo Epics without stacking In Progress
                        - do not create, sequence, or assign type:story implementation work; PM owns delivery topology
                        - do not create type:task QA test work; QA owns test-engineering work derived from epic acceptance intent
                        - QA-created type:bug + work:defect items are evidence objects awaiting product triage
                        - for a new defect, Product Owner classifies it by setting the correct type:epic parent and product
                          priority before PM routes repair
                        - if the defect proves a previously Done epic no longer satisfies its accepted behavior, move that
                          epic back to In Progress before repair routing; Done must reflect current product truth
                        - do not assign the repair owner; PM owns delivery assignment and synchronization
                        - never accept an epic Done while required child delivery/test work is unfinished, a relevant
                          defect remains open, or QA evidence does not demonstrate the promised behavior
                        - comments preserve product decisions/evidence but never invent workflow states
                        - lifecycle uses only Plane states Backlog, Todo, In Progress, Done, Cancelled
                        - for assignment changes, use update_work_item with the complete assignees array; do not use
                          manage_work_item_assignee in this profile

                        SKILL: plane-query-recipes
                        - Discovery recipe: list_work_items(project_id=<verified>, fields="id,name,sequence_id,state,priority,assignees").
                          Use this sparse queue view for lifecycle, priority, and ownership. Do not add labels or parent.
                        - Classification recipe: retrieve_work_item(project_id=<verified>, work_item_id=<selected UUID>,
                          expand="labels", fields="id,name,sequence_id,state,labels"). Always include exactly these fields
                          for classification. Never omit fields and never include assignees in this call.
                        - Description recipe: retrieve_work_item(project_id=<verified>, work_item_id=<selected UUID>,
                          fields="id,name,sequence_id,state,description_html"). Do not include labels or assignees.
                        - Parent recipe: retrieve_work_item(project_id=<verified>, work_item_id=<selected UUID>,
                          fields="id,name,sequence_id,state,parent"). Never expand parent.
                        - Ownership and classification are deliberately separate calls. Never attempt to retrieve
                          assignees and expanded labels in the same WorkItemDetail response.
                        - If one exact recipe fails with a WorkItemDetail assignee/label/parent shape error, stop that
                          retrieval strategy for the pass. Never repeat an equivalent failing shape on another item.
                        - classification is authoritative only when the expected labels are actually attached
                        - type:epic = product outcome; type:story = implementation delivery; type:task = QA test work;
                          type:bug = defect evidence
                        - every classified work item carries exactly one type:* label; work:* labels are additional flow
                          classification and never substitute for type:* or lifecycle state
                        - sparse list_work_items may omit parent; retrieve a selected item when parent or rich description is needed
                        - do not infer classification from title, role, lifecycle state, wording, or hierarchy
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
