package us.nusun.llmshare.mcp;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class McpStartupHealthCheck {
    private static final Logger LOG = LoggerFactory.getLogger(McpStartupHealthCheck.class);

    @Inject
    McpClientFactory mcpClientFactory;

    void onStartup(@Observes StartupEvent event) {
        mcpClientFactory.logStartupConfiguration();
        if (!mcpClientFactory.startupCheckEnabled()) {
            LOG.info("MCP startup health check disabled; clients will initialize lazily on first use.");
            return;
        }

        boolean failFast = mcpClientFactory.startupCheckFailFast();
        for (String identityKey : new String[]{"director", "project-manager", "product-owner", "application-developer"}) {
            runCheck(identityKey, "mattermost", failFast);
            runCheck(identityKey, "gitea", failFast);
            runCheck(identityKey, "plane", failFast);
        }
    }

    private void runCheck(String identityKey, String serviceName, boolean failFast) {
        try {
            mcpClientFactory.checkHealth(identityKey, serviceName);
        } catch (RuntimeException e) {
            if (failFast) {
                throw e;
            }
            LOG.warn("MCP startup health check failed but startup will continue: {}", e.getMessage());
        }
    }
}
