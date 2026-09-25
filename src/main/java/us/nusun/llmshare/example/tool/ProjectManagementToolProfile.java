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
    private final GiteaMcpServer gitea;
    private final int maxResultTokens;

    @Inject
    public ProjectManagementToolProfile(
            PlaneMcpServer plane,
            GiteaMcpServer gitea,
            @ConfigProperty(name = "agent.tool.profile.project-management.max-result-tokens", defaultValue = "5000") int maxResultTokens) {
        this.plane = plane;
        this.gitea = gitea;
        this.maxResultTokens = maxResultTokens;
    }

    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder(ID)
                .mcpServer(plane.definition())
                .mcpServer(gitea.definition())
                .allowTools(GiteaMcpServer.ID,
                        "get_me",
                        "list_my_repos",
                        "list_org_repos",
                        "search_repos",
                        "get_repository_tree",
                        "get_dir_contents",
                        "get_file_contents",
                        "list_branches",
                        "list_commits",
                        "get_commit",
                        "list_pull_requests",
                        "pull_request_read")
                .promptGuidance("""
                        - find direct existing projects and work items before creating anything
                        - work on only the project or work item named by the current step
                        - Plane is the sole source of truth for planned work, assignments, status, blockers, dependencies, priorities, and delivery planning
                        - Gitea reads are for canonical product acceptance and linked implementation/review evidence only; do not coordinate planned work in Gitea
                        - the canonical Gitea web/clone base is https://gitea.apps.cluster.lab.nusun.us; when constructing or interpreting repository links, use that host rather than github.com or an invented/internal hostname
                        - avoid duplicate projects or work items
                        - record blockers plainly with owner and evidence
                        - keep each work item small; split oversized items and move detail into children instead of copying it
                        - when splitting work, make the parent summarize instead of duplicating child content
                        - keep product discovery in Backlog, PM-ready implementation work in Todo, active assigned work in In Progress, and verified outcomes in Done

                        SKILL: mission-anchor-reuse
                        - once a Plane project UUID, work-item UUID/identifier, Gitea owner/repo, branch, or PR is verified, reuse that exact locator
                        - never shorten or reconstruct UUIDs; a human key such as RCY-2 is an identifier, not a UUID
                        - do not relist projects or repositories just to rediscover a known resource; rediscover only after direct lookup fails or fresh evidence says the anchor is stale

                        SKILL: plane-work-item-navigation
                        - scope Plane work-item operations to the verified project id
                        - prefer retrieve_work_item_by_identifier for human keys and retrieve_work_item only for complete UUIDs returned by Plane
                        - use sparse list_work_items for discovery and queue inspection; retrieve a rich item only after selecting it and only when its richer fields are needed
                        - if rich retrieval fails because of response shape/deserialization, use the sparse list representation for facts it proves rather than retrying the same shape
                        - an omitted field is UNKNOWN, not negative evidence; never infer no parent, no label, no assignee, or no dependency from a projection that did not return that field

                        SKILL: plane-work-classification
                        - use labels as durable classification only: work:product for PO-owned top-level user outcomes; work:implementation for PM-owned implementation children
                        - these labels do not represent lifecycle state; lifecycle remains Backlog, Todo, In Progress, Done, Cancelled
                        - when creating a product parent, ensure work:product exists and apply it; when creating an implementation child, ensure work:implementation exists and apply it
                        - never put work:implementation on a product parent or work:product on an implementation child
                        - queue-depth calculations must count only the matching classification label
                        - if labels are absent from the read, classification is unknown until labels are read; do not guess from title or description

                        SKILL: bounded-platform-navigation
                        - for a known Gitea PR or file, read that exact PR/path; use get_repository_tree for repository-root discovery rather than guessing a directory such as '.'
                        - stop reading when the current planning/delivery question has enough evidence; do not enumerate unrelated project or repository state

                        SKILL: capability-aware-recovery
                        - on a schema/validation error, correct arguments once; on 404, resolve the exact locator once; on response-shape failure, switch to a sparse/supported representation
                        - never repeat an equivalent failed call without new evidence
                        """)
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
                .defaultArgument(PlaneMcpServer.ID, "list_work_items", "fields", "id,name,sequence_id,state,priority,assignees,labels")
                .defaultArgument(PlaneMcpServer.ID, "list_work_items", "per_page", 12)
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
