package us.nusun.llmshare.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.framework.model.McpServerDefinition;

@ApplicationScoped
public class MattermostMcpServer {
    public static final String ID = "mattermost";

    private final McpServerDefinition definition;

    @Inject
    public MattermostMcpServer(@ConfigProperty(name = "agent.tool.mcp.mattermost.url", defaultValue = "") String url) {
        this.definition = McpServerDefinition.builder(ID)
                .url(url)
                .transportType("streamableHttp")
                .authHeaderName("Authorization")
                .authHeaderValueFormat("Bearer %s")
                .build();
    }

    public McpServerDefinition definition() {
        return definition;
    }
}
