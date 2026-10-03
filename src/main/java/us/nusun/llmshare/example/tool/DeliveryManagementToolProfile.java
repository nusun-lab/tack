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
                        "manage_work_item_assignee",
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
                        "list_states",
                        "list_work_item_types",
                        "retrieve_work_item_type",
                        "resolve_work_item_type")
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
                        - PM owns delivery flow, dependencies, assignment, WIP, handoffs, and lifecycle reconciliation
                        - Story decomposition follows the Architect-established system/component repository map; never
                          invent implementation topology before repository architecture is ready
                        - Product Owner owns Epic creation/priority/acceptance; never create, reprioritize, or close Epics
                        - normal implementation is a Story child of an Epic and carries work:implementation
                        - resolve Story once per project and reuse the exact type id; ensure work:implementation exists
                        - QA owns Task children carrying work:test; never create or complete QA test work for QA
                        - QA records product failures as initially unparented Bugs carrying work:defect; PO owns
                          classification into the correct Epic plus product priority; PM owns repair assignment
                        - after PO triage, route prioritized defects before creating speculative new work
                        - assign executable Todo work only to an engineer with an authoritative active AgentRun; otherwise
                          leave otherwise-ready work Backlog until that role is active
                        - assign repair Bugs directly to active DEV or ARCH; do not manufacture a second repair Story
                        - Developer and Architect claim assigned Todo work to In Progress themselves
                        - the assigned engineer owns Story lifecycle through Done; PM never closes a Story on the engineer's behalf
                        - work:defect remains In Progress through repair merge until QA retests and closes the Bug
                        - keep source WIP low: normally one In Progress source unit per DEV/ARCH and at most one Todo
                          source unit queued ahead for each; additional foreseeable work can remain Backlog
                        - prefer finishing the active Epic over expanding parallel work
                        - Gitea reads are delivery evidence only; PM never creates repositories or mutates source
                        - lifecycle uses only Backlog, Todo, In Progress, Done, Cancelled

                        SKILL: typed-work-navigation
                        - sparse list_work_items includes type_id but not reliable parent detail; select first, retrieve
                          the exact item when parent/description is required
                        - type ids and parent UUIDs are authoritative; never classify by title wording
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
