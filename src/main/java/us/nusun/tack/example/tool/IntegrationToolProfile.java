package us.nusun.tack.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.tack.framework.inventory.ToolProfileProvider;
import us.nusun.tack.framework.model.ToolProfile;

import java.util.List;

@ApplicationScoped
public class IntegrationToolProfile implements ToolProfileProvider {
    public static final String ID = "integration";

    private final PlaneMcpServer plane;
    private final GiteaMcpServer gitea;
    private final int maxResultTokens;

    @Inject
    public IntegrationToolProfile(
            PlaneMcpServer plane,
            GiteaMcpServer gitea,
            @ConfigProperty(name = "agent.tool.profile.source-control.max-result-tokens", defaultValue = "6000") int maxResultTokens) {
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
                        "workitem",
                        "workitem_comment")
                .allowTools(GiteaMcpServer.ID,
                        "search_repos",
                        "list_pull_requests",
                        "pull_request_read",
                        "pull_request_write",
                        "get_commit")
                .promptGuidance("""
                        - this profile reads linked Plane context and performs Gitea pull-request merges; use only
                          workitem=retrieve/retrieve_by_identifier and workitem_comment=list/retrieve for Plane
                        - the calling routine owns candidate selection, ordering, batching, and workflow eligibility
                        - reuse exact verified Plane work-item UUIDs and Gitea owner/repo/PR locators; never reconstruct them
                        - Gitea is authoritative for current pull-request/source state
                        - immediately before a merge, fresh-read the exact PR evidence needed to verify its current head,
                          base, mergeability/conflict state, and validation evidence requested by the routine
                        - use pull_request_write only for merge operations in this profile
                        - do not use Plane mutation actions or manufacture comment-based approval states

                        SKILL: plane-work-classification
                        - Tack work classification is represented by attached labels epic, story, test, or defect
                        - these labels are unrelated to Plane's native work-item type and type_id; never use type_id to
                          determine Tack classification

                        SKILL: plane-query-recipes
                        - Classification: workitem(action="retrieve", project_id=<verified>,
                          workitem_id=<selected UUID>, expand="labels",
                          fields="id,name,sequence_id,state,labels")
                        - Description: workitem(action="retrieve", project_id=<verified>,
                          workitem_id=<selected UUID>, fields="id,name,sequence_id,state,description_html")

                        SKILL: capability-aware-recovery
                        - on 404, resolve the exact locator once; on schema/validation error, correct arguments once
                        - if a PR is already closed/merged or an operation is unsupported, stop rather than retrying an
                          equivalent mutation
                        """)
                .hideArgument(PlaneMcpServer.ID, "workitem", "pql")
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
