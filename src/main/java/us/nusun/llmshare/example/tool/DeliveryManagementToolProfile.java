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
                        - this profile mutates Plane delivery data and reads Gitea delivery evidence; the calling routine
                          defines which delivery mutation is appropriate
                        - before mutating an existing work item, retrieve its attached labels and verify classification;
                          never infer classification from title, state, assignee, hierarchy, or wording
                        - Tack work-item kinds are represented by exactly one attached type:* label; work:* labels are
                          additional classification and never lifecycle state
                        - use update_work_item with the complete assignees array for assignment changes
                        - Gitea access in this profile is read-only evidence; do not create repositories or mutate source
                        - a successful Plane relation read is authoritative for blockers; an error/validation payload is
                          not blocker evidence
                        - if relation reading fails, use only explicit blocker information on the selected item and report
                          dependency state as unknown when it cannot be verified

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
