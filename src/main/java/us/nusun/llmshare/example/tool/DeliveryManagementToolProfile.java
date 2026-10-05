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
                        "project",
                        "member",
                        "workitem",
                        "workitem_activity",
                        "workitem_comment",
                        "workitem_relation",
                        "label",
                        "state")
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
                        - use only these Plane actions here: project=list/retrieve;
                          member=me/list_workspace/list_project;
                          workitem=list/retrieve/retrieve_by_identifier/search/create/update/manage_label;
                          workitem_activity=list; workitem_comment=list/retrieve/create/update;
                          workitem_relation=list/create/delete; label=list/create; state=list
                        - before mutating an existing work item, retrieve its attached labels and verify classification;
                          never infer classification from title, state, assignee, hierarchy, or wording
                        - Tack work classification is represented by exactly one attached Plane label: epic, story, test,
                          or defect; classification labels never represent lifecycle state
                        - these labels are unrelated to Plane's native work-item type and type_id; never use type_id to
                          determine Tack classification
                        - use workitem(action="update", ...) with the complete assignees array for assignment changes
                        - Gitea access in this profile is read-only evidence; do not create repositories or mutate source
                        - a successful Plane relation read is authoritative for blockers; an error/validation payload is
                          not blocker evidence
                        - if relation reading fails, use only explicit blocker information on the selected item and report
                          dependency state as unknown when it cannot be verified

                        SKILL: plane-query-recipes
                        - Discovery: workitem(action="list", project_id=<verified>, per_page=12,
                          fields="id,name,sequence_id,state,priority,assignees")
                        - Classification: workitem(action="retrieve", project_id=<verified>,
                          workitem_id=<selected UUID>, expand="labels",
                          fields="id,name,sequence_id,state,labels")
                        - Description: workitem(action="retrieve", project_id=<verified>,
                          workitem_id=<selected UUID>, fields="id,name,sequence_id,state,description_html")
                        - Parent: workitem(action="retrieve", project_id=<verified>,
                          workitem_id=<selected UUID>, fields="id,name,sequence_id,state,parent"); never expand parent
                        - a field omitted by a sparse projection is unknown, never negative evidence
                        """)
                .hideArgument(PlaneMcpServer.ID, "workitem", "pql")
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
