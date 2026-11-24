package us.nusun.tack.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.tack.framework.inventory.ToolProfileProvider;
import us.nusun.tack.framework.model.ToolProfile;

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
        return List.of(PlaneWork.profile(ID, plane)
                .mcpServer(gitea.definition())
                .allowTool(PlaneMcpServer.ID, "workitem_relation")
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
                          workitem_relation=list/create; label=list/create; state=list
                        - before mutating an existing work item, retrieve its attached labels and verify classification;
                          never infer classification from title, state, assignee, hierarchy, or wording
                        """ + PlaneWork.CLASSIFICATION + """
                        - use workitem(action="update", ...) with the complete assignees array for assignment changes
                        - Gitea access in this profile is read-only evidence; do not create repositories or mutate source
                        - built-in dependency relations are direct: use workitem_relation(action="list", ...) to inspect
                          them and workitem_relation(action="create", relation_type=<built-in>, ...) to create them; valid
                          built-in types are blocking, blocked_by, start_before, start_after, finish_before, and finish_after
                        - do not call list_definitions for built-in dependency relations; relation definitions are only for
                          custom relationships
                        - relation deletion is not available in the current Plane CE API; do not attempt
                          workitem_relation(action="delete")
                        - a successful Plane relation read is authoritative for blockers; an error/validation payload is
                          not blocker evidence
                        - if relation reading fails, use only explicit blocker information on the selected item and report
                          dependency state as unknown when it cannot be verified

                        """ + PlaneWork.QUERIES)
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
