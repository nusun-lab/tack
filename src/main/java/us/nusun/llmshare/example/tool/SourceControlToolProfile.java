package us.nusun.llmshare.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.framework.inventory.ToolProfileProvider;
import us.nusun.llmshare.framework.model.McpServerDefinition;
import us.nusun.llmshare.framework.model.ToolProfile;

import java.util.List;

@ApplicationScoped
public class SourceControlToolProfile implements ToolProfileProvider {
    public static final String ID = "source-control";

    @ConfigProperty(name = "agent.tool.mcp.gitea.url", defaultValue = "")
    String url = "";

    @ConfigProperty(name = "agent.tool.profile.source-control.max-result-tokens", defaultValue = "6000")
    int maxResultTokens = 6000;

    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder(ID)
                .mcpServer(McpServerDefinition.builder("gitea")
                        .url(url)
                        .transportType("streamableHttp")
                        .authHeaderName("Authorization")
                        .authHeaderValueFormat("token %s")
                        .build())
                .promptGuidance("""
                                 - read before writing
                                 - use durable repo evidence
                                 - always create repositories in the agent-workspace organization
                                 - avoid duplicate branches, issues, or pull requests
                                 - no not use native wiki features
                                 - wikis should be their own repo
                                 - wikis should be in markdown
                                 - wikis should be well-linked so it's possible to navigate to any page starting from the index (readme.md)
                                 - do not read entire wikis, try to read only what you need
                                 - generally reference the 'url' attribute of repositories, not clone or ssh
                                 """)
                .denyTools("gitea", "delete_repository", "delete_branch", "wiki_read", "wiki_write")
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
