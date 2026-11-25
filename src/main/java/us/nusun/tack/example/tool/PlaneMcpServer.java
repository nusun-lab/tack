package us.nusun.tack.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.tack.framework.tool.McpServerDefinition;

@ApplicationScoped
public class PlaneMcpServer {
    public static final String ID = "plane";

    private final McpServerDefinition definition;

    @Inject
    public PlaneMcpServer(
            @ConfigProperty(name = "agent.tool.mcp.plane.url", defaultValue = "") String url,
            @ConfigProperty(name = "agent.tool.mcp.plane.workspace-slug", defaultValue = "") String workspaceSlug) {
        McpServerDefinition.Builder plane = McpServerDefinition.builder(ID)
                .url(url)
                .transportType("streamableHttp")
                .authHeaderName("Authorization")
                .authHeaderValueFormat("Bearer %s");
        if (workspaceSlug != null && !workspaceSlug.isBlank()) {
            plane.extraHeader("x-workspace-slug", workspaceSlug);
        }
        this.definition = plane.build();
    }

    public McpServerDefinition definition() {
        return definition;
    }
}
