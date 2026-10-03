package us.nusun.llmshare.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.framework.inventory.ToolProfileProvider;
import us.nusun.llmshare.framework.model.ToolProfile;

import java.util.List;

@ApplicationScoped
public class DeliveryManagementToolProfile implements ToolProfileProvider {
    public static final String ID = "delivery-management";

    private final PlaneMcpServer plane;
    private final GiteaMcpServer gitea;
    private final int maxResultTokens;

    @Inject
    public DeliveryManagementToolProfile(
            PlaneMcpServer plane,
            GiteaMcpServer gitea,
            @ConfigProperty(name = "agent.tool.profile.project-management.max-result-tokens", defaultValue = "5000")
            int maxResultTokens) {
        this.plane = plane;
        this.gitea = gitea;
        this.maxResultTokens = maxResultTokens;
    }

    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder(ID)
                .mcpServer(plane.definition())
                .mcpServer(gitea.definition())
                .allowTools(PlaneMcpServer.ID,
                        "get_me",
                        "list_projects",
                        "retrieve_project",
                        "get_project_members",
                        "get_workspace_members",
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
                        "list_work_item_relations",
                        "create_work_item_relation",
                        "remove_work_item_relation",
                        "list_labels",
                        "create_label",
                        "list_states")
                .allowTools(GiteaMcpServer.ID,
                        "get_me",
                        "search_repos",
                        "get_repository_tree",
                        "get_file_contents",
                        "list_branches",
                        "list_commits",
                        "get_commit",
                        "list_pull_requests",
                        "pull_request_read")
                .promptGuidance("""
                        - PM owns delivery flow, dependencies, assignment, WIP, handoffs, and lifecycle reconciliation;
                          continuously keep Plane coherent, assigned, and executable
                        - before mutating an existing work item, verify its attached labels establish its classification;
                          unknown classification is a stop condition, never a reason to infer from title/state/assignee
                        - every non-Backlog work item must have an owner appropriate to verified classification; repair
                          ownership gaps without changing another role's lifecycle ownership
                        - PO-authored Given/When/Then Epic acceptance is the authoritative behavioral boundary for Story
                          decomposition; do not silently broaden or reinterpret it
                        - Story decomposition follows the Architect-established system/component repository map; never
                          invent implementation topology before repository architecture is ready
                        - Product Owner owns type:epic creation/priority/acceptance; never create, reprioritize, or close
                          epics, and never assign an Epic to DEV/ARCH/QA
                        - normal implementation is a child of a type:epic carrying both type:story and work:implementation
                        - ensure type:story and work:implementation labels exist and attach both explicitly
                        - QA owns children carrying type:task + work:test; never create or complete QA test work for QA
                        - QA records product failures as initially unparented type:bug + work:defect items; PO owns
                          association to the correct epic plus product priority; PM owns repair assignment
                        - after PO triage, route prioritized defects before creating speculative new work
                        - durable Plane readiness is independent of ephemeral AgentRun presence: PM may stage assigned Todo
                          work for the intended DEV/ARCH identity even while that AgentRun is temporarily absent
                        - AgentRun presence controls who can execute now; it never moves existing Plane work backward
                        - assign repair type:bug + work:defect items directly to the intended DEV or ARCH identity; do not
                          manufacture a repair story
                        - Developer and Architect claim assigned Todo work to In Progress themselves
                        - the assigned engineer owns type:story + work:implementation lifecycle through Done; PM never closes it
                        - work:defect remains In Progress through repair merge until QA retests and closes the type:bug item
                        - keep source flow supplied independently per engineer: target roughly 2-3 useful In Progress
                          source units and 2-3 assigned executable Todo units for each active DEV/ARCH when enough
                          independent work exists; PR-integration wait or external wait does not consume active coding
                          capacity; these are targets, not hard ceilings; ARCH work does not consume DEV capacity and DEV
                          work does not consume ARCH capacity
                        - engineering execution does not limit PM planning depth; maintain the reasonable future
                          Story/dependency graph in Backlog so executable work can be promoted before capacity runs dry
                        - prefer finishing valuable current work while preserving flow; do not serialize independent work
                          merely for cleanliness
                        - Gitea reads are delivery evidence only; PM never creates repositories or mutates source
                        - lifecycle uses only Backlog, Todo, In Progress, Done, Cancelled
                        - PM may promote legitimate Backlog delivery work to Todo, but never moves Todo/In Progress/Done/
                          Cancelled work backward for staffing, capacity, dependency bookkeeping, or queue shaping
                        - Done and Cancelled are terminal for ordinary PM flow management
                        - child implementation work beneath a Todo Epic remains Backlog until PO activates that Epic
                        - for assignment changes, use update_work_item with the complete assignees array; do not use
                          manage_work_item_assignee in this profile

                        SKILL: dependency-navigation
                        - a successful Plane relation read is authoritative for blockers; a validation/error payload is
                          never successful relation evidence and must not be interpreted as a blocker relationship
                        - if relation reading fails, inspect only the selected Story's explicit blocker text such as
                          "Blocked by: PCT-6", then resolve that exact blocker and use its current Plane state
                        - a blocker explicitly verified Done satisfies that dependency; do not invent a dependency between
                          sibling Stories from sequence order, current assignee, or another Story being In Progress
                        - when dependency evidence remains unknown, report it unknown instead of manufacturing a blocker

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
                        - classification is authoritative only from attached labels; never infer type from title wording
                        - every classified work item carries exactly one type:* label
                        - retrieve a selected item when parent or description is required; parent UUID is authoritative
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
