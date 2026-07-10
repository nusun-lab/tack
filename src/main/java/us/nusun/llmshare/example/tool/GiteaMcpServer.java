package us.nusun.llmshare.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.framework.model.McpServerDefinition;

@ApplicationScoped
public class GiteaMcpServer {
    public static final String ID = "gitea";

    private final McpServerDefinition definition;

    @Inject
    public GiteaMcpServer(@ConfigProperty(name = "agent.tool.mcp.gitea.url", defaultValue = "") String url) {
        this.definition = McpServerDefinition.builder(ID)
                .url(url)
                .transportType("streamableHttp")
                .authHeaderName("Authorization")
                .authHeaderValueFormat("token %s")
                .build();
    }

    public McpServerDefinition definition() {
        return definition;
    }
}
