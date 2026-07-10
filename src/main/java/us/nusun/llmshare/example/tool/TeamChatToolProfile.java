package us.nusun.llmshare.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.framework.inventory.ToolProfileProvider;
import us.nusun.llmshare.framework.model.ToolProfile;

import java.util.List;

@ApplicationScoped
public class TeamChatToolProfile implements ToolProfileProvider {
    public static final String ID = "team-chat";

    private final MattermostMcpServer mattermost;
    private final int maxResultTokens;

    @Inject
    public TeamChatToolProfile(
            MattermostMcpServer mattermost,
            @ConfigProperty(name = "agent.tool.profile.team-chat.max-result-tokens", defaultValue = "3000") int maxResultTokens) {
        this.mattermost = mattermost;
        this.maxResultTokens = maxResultTokens;
    }

    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder(ID)
                .mcpServer(mattermost.definition())
                .promptGuidance("""
                        - read recent channel context before posting
                        - Mattermost is for concise communication, questions, handoffs, and notifications
                        - durable project state belongs in Plane
                        - durable implementation state belongs in Gitea
                        - link to authoritative Plane work items, Gitea repositories, pull requests, releases, or canonical documentation pages
                        - avoid generic spam
                        - do not recreate durable Plane or Gitea state in Mattermost
                        - do not read entire channels, stick to recent messages
                        """)
                .denyTools(MattermostMcpServer.ID,
                        "delete_channel",
                        "delete_post",
                        "delete_message",
                        "leave_channel",
                        "mark_channel_viewed",
                        "add_reaction",
                        "get_reactions",
                        "remove_reaction",
                        "pin_message",
                        "unpin_message",
                        "create_bookmark",
                        "delete_bookmark",
                        "list_bookmarks",
                        "update_bookmark",
                        "update_bookmark_sort_order")
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
