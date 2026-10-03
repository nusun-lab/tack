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
                        - Mattermost is for concise communication, questions, handoffs, notifications, and useful team coordination
                        - do not require an existing conversation before posting; initiate one concise message when a meaningful handoff, blocker, question, merge, workspace transition, or other event would help a current teammate act sooner
                        - use the current-mission shared team channel for normal team questions, handoffs, blockers, decisions, and awareness
                        - when the verified channel id is known, use it directly; when it is unknown, list_teams once and use the returned team id with get_channel_by_name for the mission channel name
                        - use list_my_channels only when the mission channel name is genuinely unknown; do not start by guessing a team id or channel name
                        - ids/names shown only in tool descriptions or examples are illustrative and must never be used as live Mattermost locators
                        - missing shared-channel access is an onboarding defect only after the channel was resolved through current Mattermost evidence; report it for repair and do not fall back to a direct channel
                        - direct-message history is not a substitute for current-mission channel context; use a DM only for genuinely private exchanges already anchored to the current mission
                        - when responding, read only the recent relevant current-mission channel context needed for that exchange
                        - post at most one message or reply for the current step
                        - durable project state belongs in Plane
                        - durable implementation state belongs in Gitea
                        - comments/messages provide evidence and context, not hidden workflow states
                        - chat phrases such as "green", "approved", "review ready", "integration ready", acknowledgments,
                          and handoff confirmations never authorize or block Plane lifecycle or Gitea integration
                        - merge authority comes from role policy and merge readiness from current Gitea/validation evidence
                        - link to authoritative Plane work items, Gitea repositories, pull requests, releases, or canonical documentation pages when useful
                        - avoid generic status chatter, ceremonial updates, and duplicate notifications
                        - do not recreate durable Plane or Gitea state in Mattermost
                        - do not read entire channels, stick to recent messages
                        """)
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
