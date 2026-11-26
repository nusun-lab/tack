package us.nusun.tack.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import us.nusun.tack.framework.tool.ToolProfileProvider;
import us.nusun.tack.framework.tool.ToolProfile;

import java.util.List;

@ApplicationScoped
public class TeamChatToolProfile implements ToolProfileProvider {
    public static final String ID = "team-chat";

    static final String DISCOVERY = """
            Reuse the verified Mattermost team and channel IDs. To recover, use list_teams and get_channel_by_name
            with the channel named by the act or anchored to the current mission.
            """;

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
                        "get_channel",
                        "get_channel_by_name",
                        "get_channel_messages",
                        "get_thread",
                        "post_message",
                        "update_message",
                        "get_user",
                        "get_user_by_username")
                .promptGuidance(DISCOVERY + """
                        Read the relevant exchange. Use chat for concise awareness or questions and link durable evidence.
                        """)
                .build());
    }
}
