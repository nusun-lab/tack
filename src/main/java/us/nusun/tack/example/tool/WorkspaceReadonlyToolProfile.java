package us.nusun.tack.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.tack.framework.inventory.ToolProfileProvider;
import us.nusun.tack.framework.model.ToolProfile;

import java.util.List;

@ApplicationScoped
public class WorkspaceReadonlyToolProfile implements ToolProfileProvider {
    public static final String ID = "workspace-readonly";

    private final PlaneMcpServer plane;
    private final GiteaMcpServer gitea;
    private final MattermostMcpServer mattermost;
    private final int maxResultTokens;

    @Inject
    public WorkspaceReadonlyToolProfile(
            PlaneMcpServer plane,
            GiteaMcpServer gitea,
            MattermostMcpServer mattermost,
            @ConfigProperty(name = "agent.tool.profile.workspace-readonly.max-result-tokens", defaultValue = "4000") int maxResultTokens) {
        this.plane = plane;
        this.gitea = gitea;
        this.mattermost = mattermost;
        this.maxResultTokens = maxResultTokens;
    }

    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder(ID)
                .mcpServer(plane.definition())
                .mcpServer(gitea.definition())
                .mcpServer(mattermost.definition())
                .allowTools(PlaneMcpServer.ID,
                        "project", "member", "workitem", "workitem_activity", "workitem_comment", "label", "state")
                .hideArgument(PlaneMcpServer.ID, "workitem", "pql")
                .permitArgumentValues(PlaneMcpServer.ID, "project", "action", "list", "retrieve")
                .permitArgumentValues(PlaneMcpServer.ID, "member", "action", "me", "list_workspace", "list_project")
                .permitArgumentValues(PlaneMcpServer.ID, "workitem", "action", "list", "retrieve", "retrieve_by_identifier", "search")
                .permitArgumentValues(PlaneMcpServer.ID, "workitem_activity", "action", "list")
                .permitArgumentValues(PlaneMcpServer.ID, "workitem_comment", "action", "list", "retrieve")
                .permitArgumentValues(PlaneMcpServer.ID, "label", "action", "list")
                .permitArgumentValues(PlaneMcpServer.ID, "state", "action", "list")
                .allowTools(GiteaMcpServer.ID,
                        "get_me",
                        "search_repos",
                        "get_repository_tree",
                        "get_dir_contents",
                        "get_file_contents",
                        "list_branches",
                        "list_commits",
                        "get_commit",
                        "list_pull_requests",
                        "pull_request_read")
                .allowTools(MattermostMcpServer.ID,
                        "get_me",
                        "list_teams",
                        "list_my_channels",
                        "get_channel",
                        "get_channel_by_name",
                        "get_channel_members",
                        "get_channel_messages",
                        "get_thread",
                        "search_messages",
                        "get_user",
                        "get_user_by_username",
                        "search_users")
                .promptGuidance("""
                        - Plane access is read-only; select an explicit action from the tool schema
                        - the calling routine determines which observations matter and what policy to apply to them
                        - use only artifacts that can be verified as belonging to the current mission; unrelated resources
                          are not evidence
                        - Plane is authoritative for Plane work data, Gitea for repository/PR data, and Mattermost for
                          conversation; external artifacts never prove that an AgentRun is active
                        - CI/workflow execution is not enabled in this Gitea environment
                        - reuse verified locators instead of rediscovering resources; never shorten or reconstruct UUIDs
                        - ids, names, URLs, and values shown only in tool descriptions/examples are illustrative, not live
                          resource locators

                        """ + PlaneWorkGuidance.CLASSIFICATION + "\n" + PlaneWorkGuidance.QUERIES + """

                        SKILL: membership-model
                        - keep active mission roster, Plane workspace membership, Plane project membership, and Mattermost
                          channel membership distinct; presence in one does not prove presence in another
                        - use member(action="list_workspace") or member(action="list_project", project_id=<verified>)
                          for the exact Plane membership set requested, and name that set explicitly in observations

                        SKILL: bounded-platform-navigation
                        - use the fewest direct lookups needed for the current observation, then stop
                        - if the Plane project UUID is unknown, use project(action="list") once; its list response is already trimmed
                        - if a Gitea owner/repo is unknown, search once with the mission/repository anchor and use only the
                          returned canonical owner/repo
                        - for repository-root discovery use get_repository_tree; for known files read the exact path
                        - if a Mattermost channel id is unknown, list teams once and resolve the known mission channel name
                          with get_channel_by_name; use list_my_channels only when the channel name is genuinely unknown
                        - do not read entire repositories, projects, wikis, or channels

                        SKILL: capability-aware-recovery
                        - on schema/validation error, correct arguments once; on 404, resolve the exact locator once; on
                          response-shape failure, switch to a sparse/supported representation
                        - do not repeat an equivalent failed call without new evidence
                        - distinguish verified observations from assumptions
                        """)
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
