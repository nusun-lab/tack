package us.nusun.llmshare.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.framework.model.McpServerDefinition;
import us.nusun.llmshare.framework.model.ToolProfile;
import us.nusun.llmshare.framework.inventory.ToolProfileProvider;

import java.util.List;

@ApplicationScoped
public class TeamChatToolProfile implements ToolProfileProvider {
    public static final String ID = "team-chat";

    @ConfigProperty(name = "agent.tool.mcp.mattermost.url", defaultValue = "")
    String url = "";

    @ConfigProperty(name = "agent.tool.mcp.mattermost.transport-type", defaultValue = "streamableHttp")
    String transportType = "streamableHttp";

    @ConfigProperty(name = "agent.tool.mcp.mattermost.auth-header-name", defaultValue = "Authorization")
    String authHeaderName = "Authorization";

    @ConfigProperty(name = "agent.tool.mcp.mattermost.auth-header-value-format", defaultValue = "Bearer %s")
    String authHeaderValueFormat = "Bearer %s";

    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder(ID)
                .mcpServer(McpServerDefinition.builder("mattermost")
                        .url(url)
                        .transportType(transportType)
                        .authHeaderName(authHeaderName)
                        .authHeaderValueFormat(authHeaderValueFormat)
                        .build())
                .promptGuidance("""
                        - read recent channel context before posting
                        - avoid generic spam
                        - prefer links to durable artifacts, work items, repository evidence, or wiki notes
                        - do not read entire channels, stick to recent messages
                        """)
                .denyTools("mattermost", "delete_channel", "delete_post")
                .maxResultCharacters(3000)
                .build());
    }
}
