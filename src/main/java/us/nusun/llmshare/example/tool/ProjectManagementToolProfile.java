package us.nusun.llmshare.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.framework.model.McpServerDefinition;
import us.nusun.llmshare.framework.model.ToolProfile;
import us.nusun.llmshare.framework.inventory.ToolProfileProvider;

import java.util.List;

@ApplicationScoped
public class ProjectManagementToolProfile implements ToolProfileProvider {
    public static final String ID = "project-management";

    @ConfigProperty(name = "agent.tool.mcp.plane.workspace-slug", defaultValue = "")
    String workspaceSlug = "";

    @ConfigProperty(name = "agent.tool.mcp.plane.url", defaultValue = "")
    String url = "";

    @ConfigProperty(name = "agent.tool.mcp.plane.transport-type", defaultValue = "streamableHttp")
    String transportType = "streamableHttp";

    @ConfigProperty(name = "agent.tool.mcp.plane.auth-header-name", defaultValue = "Authorization")
    String authHeaderName = "Authorization";

    @ConfigProperty(name = "agent.tool.mcp.plane.auth-header-value-format", defaultValue = "Bearer %s")
    String authHeaderValueFormat = "Bearer %s";

    @Override
    public List<ToolProfile> toolProfiles() {
        McpServerDefinition.Builder plane = McpServerDefinition.builder("plane")
                .url(url)
                .transportType(transportType)
                .authHeaderName(authHeaderName)
                .authHeaderValueFormat(authHeaderValueFormat);
        if (workspaceSlug != null && !workspaceSlug.isBlank()) {
            plane.extraHeader("x-workspace-slug", workspaceSlug);
        }
        return List.of(ToolProfile.builder(ID)
                .mcpServer(plane.build())
                .promptGuidance("""
                        - find existing projects and work items before creating anything
                        - avoid duplicates
                        - record blockers plainly with owner, evidence, and next action
                        - no single work item should be too long
                        - strive to break large work items down into small tasks
                        - when breaking items into smaller pieces, make sure the parent item summarizes instead of duplicating the content of its children""")
                .denyTools("plane", "delete_project", "delete_work_item")
                .defaultArgument("plane", "list_projects", "fields", "id,name,identifier")
                .defaultArgument("plane", "list_work_items", "fields", "id,name,sequence_id,state,priority,assignees")
                .maxResultCharacters(5000)
                .build());
    }
}
