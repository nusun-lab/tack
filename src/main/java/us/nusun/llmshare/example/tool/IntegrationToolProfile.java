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
                        "pull_request_read",
                        "pull_request_write",
                        "get_commit")
                .promptGuidance("""
                        - Plane INTEGRATION READY is the authoritative Project Manager approval for integration
                        - reread the current Plane work item and its latest handoff immediately before any merge
                        - verify the exact repository, pull request, reviewed head, expected base, reviewed time, validation, and acceptance reference from that handoff
                        - use Gitea only to verify current pull-request/source state and perform the final merge
                        - do not infer Project Manager approval from Gitea reviews or comments
                        - do not merge if the reviewed head changed, the expected base changed, the pull request conflicts or is not mergeable, or newer review/comment activity makes the reviewed surface stale
                        - when the handoff is stale or ambiguous, make no source-control mutation; Project Management owns redisposition through Plane
                        """)
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
