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
                .allowTools(MattermostMcpServer.ID,
                        "get_me",
                        "list_my_channels",
                        "get_channel",
                        "get_channel_by_name",
                        "get_channel_messages",
                        "get_thread",
                        "search_messages",
                        "create_direct_channel",
                        "post_message",
                        "update_message",
                        "get_user",
                        "get_user_by_username",
                        "search_users")
                .promptGuidance("""
                        - Mattermost is for concise communication, questions, handoffs, notifications, and useful team coordination
                        - do not require an existing conversation before posting; initiate one concise message when a meaningful handoff, blocker, question, merge, workspace transition, or other event would help a current teammate act sooner
                        - when responding, read only the recent relevant channel context needed for that exchange
                        - post at most one message or reply for the current step
                        - durable project state belongs in Plane
                        - durable implementation state belongs in Gitea
                        - comments/messages provide evidence and context, not hidden workflow states
                        - link to authoritative Plane work items, Gitea repositories, pull requests, releases, or canonical documentation pages when useful
                        - avoid generic status chatter, ceremonial updates, and duplicate notifications
                        - do not recreate durable Plane or Gitea state in Mattermost
                        - do not read entire channels, stick to recent messages
                        """)
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
