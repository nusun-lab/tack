package us.nusun.tack.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import us.nusun.tack.framework.tool.ToolProfileProvider;
import us.nusun.tack.framework.tool.ToolProfile;

import java.util.List;

@ApplicationScoped
public class TeamChatToolProfile implements ToolProfileProvider {
    public static final String ID = "team-chat";

    private final MattermostMcpServer mattermost;

    @Inject
    public TeamChatToolProfile(
            MattermostMcpServer mattermost) {
        this.mattermost = mattermost;
    }

    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder(ID)
                .mcpServer(mattermost.definition())
                .allowTools(MattermostMcpServer.ID,
                        "get_me",
                        "list_teams",
                        "list_my_channels",
                        "get_channel",
                        "get_channel_by_name",
                        "get_channel_messages",
                        "get_thread",
                        "search_messages",
                        "post_message",
                        "update_message",
                        "get_user",
                        "get_user_by_username",
                        "search_users")
                .promptGuidance("""
                        - this profile provides Mattermost conversation; the calling routine decides whether communication
                          is useful and what role/workflow policy the message should express
                        - when a verified channel id is known, use it directly
                        - when the channel id is unknown but the mission channel name is known, list teams once and resolve
                          it with get_channel_by_name; use list_my_channels only when the channel name is genuinely unknown
                        - ids/names shown only in tool descriptions/examples are illustrative and are not live locators
                        - read only the recent relevant context needed for the current exchange
                        - shared-channel conversation is the default for ordinary team coordination; use direct messages
                          only when the calling routine intentionally requires a private exchange
                        - messages provide conversational evidence/context and are not authoritative Kaneo or Gitea state
                        - link authoritative Kaneo/Gitea/documentation resources when useful instead of recreating them
                        - avoid duplicate notifications, generic status chatter, and broad channel reads
                        """)
                .build());
    }
}
