package us.nusun.llmshare.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.framework.inventory.ToolProfileProvider;
import us.nusun.llmshare.framework.model.ToolProfile;

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
                        "retrieve_work_item",
                        "retrieve_work_item_by_identifier",
                        "list_work_item_comments",
                        "retrieve_work_item_comment")
                .allowTools(GiteaMcpServer.ID,
                        "search_repos",
                        "list_pull_requests",
                        "pull_request_read",
                        "pull_request_write",
                        "get_commit")
                .promptGuidance("""
                        - this profile reads linked Plane context and performs Gitea pull-request merges; the calling
                          routine owns candidate selection, ordering, batching, and workflow eligibility
                        - reuse exact verified Plane work-item UUIDs and Gitea owner/repo/PR locators; never reconstruct them
                        - Gitea is authoritative for current pull-request/source state
                        - immediately before a merge, fresh-read the exact PR evidence needed to verify its current head,
                          base, mergeability/conflict state, and validation evidence requested by the routine
                        - use pull_request_write only for merge operations in this profile
                        - this profile does not mutate Plane lifecycle or manufacture comment-based approval states

                        SKILL: plane-query-recipes
                        - Classification: retrieve_work_item(project_id=<verified>, work_item_id=<selected UUID>,
                          expand="labels", fields="id,name,sequence_id,state,labels")
                        - Description: retrieve_work_item(project_id=<verified>, work_item_id=<selected UUID>,
                          fields="id,name,sequence_id,state,description_html")
                        - keep expanded-label and other rich WorkItemDetail shapes separate
                        - after a WorkItemDetail response-shape failure, do not retry an equivalent failing shape

                        SKILL: capability-aware-recovery
                        - on 404, resolve the exact locator once; on schema/validation error, correct arguments once
                        - if a PR is already closed/merged or an operation is unsupported, stop rather than retrying an
                          equivalent mutation
                        """)
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
