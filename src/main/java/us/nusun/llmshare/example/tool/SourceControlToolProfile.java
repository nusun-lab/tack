package us.nusun.llmshare.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.framework.model.McpServerDefinition;
import us.nusun.llmshare.framework.model.ToolProfile;
import us.nusun.llmshare.framework.inventory.ToolProfileProvider;

import java.util.List;

@ApplicationScoped
public class SourceControlToolProfile implements ToolProfileProvider {
    public static final String ID = "source-control";

    @ConfigProperty(name = "agent.mcp.gitea.url", defaultValue = "")
    String url = "";

    @ConfigProperty(name = "agent.mcp.gitea.transport-type", defaultValue = "streamableHttp")
    String transportType = "streamableHttp";

    @ConfigProperty(name = "agent.mcp.gitea.auth-header-name", defaultValue = "Authorization")
    String authHeaderName = "Authorization";

    @ConfigProperty(name = "agent.mcp.gitea.auth-header-value-format", defaultValue = "token %s")
    String authHeaderValueFormat = "token %s";

    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder(ID)
                .mcpServer(McpServerDefinition.builder("gitea")
                        .url(url)
                        .transportType(transportType)
                        .authHeaderName(authHeaderName)
                        .authHeaderValueFormat(authHeaderValueFormat)
                        .build())
                .denyTools("gitea", "delete_repository", "delete_branch")
                .maxResultCharacters(6000)
                .build());
    }
}
