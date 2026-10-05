package us.nusun.llmshare.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.framework.inventory.ToolProfileProvider;
import us.nusun.llmshare.framework.model.ToolProfile;

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
                        "get_me",
                        "list_projects",
                        "retrieve_project",
                        "get_project_members",
                        "get_workspace_members",
                        "list_work_items",
                        "retrieve_work_item",
                        "retrieve_work_item_by_identifier",
                        "search_work_items",
                        "list_work_item_activities",
                        "list_work_item_comments",
                        "retrieve_work_item_comment",
                        "list_labels",
                        "list_states")
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
                        - this profile is read-only; the calling routine determines which observations matter and what
                          policy to apply to them
                        - use only artifacts that can be verified as belonging to the current mission; unrelated resources
                          are not evidence
                        - Plane is authoritative for Plane work data, Gitea for repository/PR data, and Mattermost for
                          conversation; external artifacts never prove that an AgentRun is active
                        - CI/workflow execution is not enabled in this Gitea environment
                        - reuse verified locators instead of rediscovering resources; never shorten or reconstruct UUIDs
                        - ids, names, URLs, and values shown only in tool descriptions/examples are illustrative, not live
                          resource locators

                        SKILL: plane-work-classification
                        - Tack work classification is represented only by attached Plane labels: epic, story, test, or defect
                        - these classification labels are unrelated to Plane's native work-item type and type_id; never use
                          type_id to determine Tack classification
                        - verify classification from attached labels: epic means Epic, story means Story, test means Test,
                          and defect means Defect
                        - every classified work item carries exactly one of these four labels

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

                        SKILL: membership-model
                        - keep active mission roster, Plane workspace membership, Plane project membership, and Mattermost
                          channel membership distinct; presence in one does not prove presence in another
                        - use workspace/project/channel membership tools to establish the specific set requested by the
                          routine and name that set explicitly in observations

                        SKILL: bounded-platform-navigation
                        - use the fewest direct lookups needed for the current observation, then stop
                        - if the Plane project UUID is unknown, list projects once with sparse id/name/identifier fields
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
                .defaultArgument(PlaneMcpServer.ID, "list_projects", "fields", "id,name,identifier")
                .defaultArgument(PlaneMcpServer.ID, "list_work_items", "fields", "id,name,sequence_id,state,priority,assignees")
                .defaultArgument(PlaneMcpServer.ID, "list_work_items", "per_page", 12)
                .hideArgument(PlaneMcpServer.ID, "list_work_items", "pql")
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
