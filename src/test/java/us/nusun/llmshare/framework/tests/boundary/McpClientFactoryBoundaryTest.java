package us.nusun.llmshare.framework.tests.boundary;

import dev.langchain4j.mcp.client.McpClient;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.logging.LogTokens;
import us.nusun.llmshare.framework.mcp.AgentIdentityConfig;
import us.nusun.llmshare.framework.mcp.McpClientFactory;
import us.nusun.llmshare.framework.model.Persona;
import us.nusun.llmshare.framework.model.ToolProfile;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpClientFactoryBoundaryTest {
    @Test
    void noneProfileReturnsNoClientsWithoutReadingServerConfig() {
        McpClientFactory factory = new McpClientFactory();

        assertEquals(List.of(), factory.clientsFor(FrameworkFixtures.personas().getFirst(), ToolProfile.builder("none").build()));
    }

    @Test
    void missingUrlOrTokenDisablesConfiguredServerClient() {
        McpClientFactory factory = factory(config(null, "http://gitea.example", null));
        Persona persona = Persona.builder("persona")
                .name("Persona")
                .identity("plane", "test.mcp.plane.token.missing", "MISSING_TOKEN_ENV", "@persona")
                .identity("gitea", "test.mcp.gitea.token.missing", "MISSING_TOKEN_ENV", "@persona")
                .build();
        ToolProfile profile = ToolProfile.builder("tools").allowAllTools("plane").allowAllTools("gitea").build();

        assertEquals(List.of(), factory.clientsFor(persona, profile));
    }

    @Test
    void createsOnlyAllowedServerClientsWithPerPersonaLazyKeys() {
        System.setProperty("test.mcp.plane.token", "plane-secret");
        try {
            McpClientFactory factory = factory(config("http://plane.example/mcp", "http://gitea.example/mcp", "workspace-a"));
            Persona persona = Persona.builder("persona-a")
                    .name("Persona A")
                    .identity("plane", "test.mcp.plane.token", "MISSING_TOKEN_ENV", "@miles-agent")
                    .identity("gitea", "test.mcp.gitea.token.missing", "MISSING_TOKEN_ENV", "@miles-agent")
                    .build();
            ToolProfile profile = ToolProfile.builder("project-management").allowAllTools("plane").build();

            List<McpClient> clients = factory.clientsFor(persona, profile);

            assertEquals(1, clients.size());
            assertEquals("plane:persona-a", clients.getFirst().key());
        } finally {
            System.clearProperty("test.mcp.plane.token");
        }
    }

    private static McpClientFactory factory(AgentIdentityConfig config) {
        McpClientFactory factory = new McpClientFactory();
        FrameworkFixtures.set(factory, "config", config);
        FrameworkFixtures.set(factory, "logTokens", new LogTokens(false));
        return factory;
    }

    private static AgentIdentityConfig config(String planeUrl, String giteaUrl, String workspaceSlug) {
        return () -> new TestMcpConfig(
                new TestLoggingConfig(),
                1000,
                500,
                1000,
                new TestServiceConfig(null, "streamableHttp", Optional.empty(), Optional.empty()),
                new TestServiceConfig(giteaUrl, "streamableHttp", Optional.empty(), Optional.empty()),
                new TestPlaneConfig(planeUrl, "streamableHttp", Optional.empty(), Optional.empty(), Optional.ofNullable(workspaceSlug)));
    }

    record TestMcpConfig(
            Logging logging,
            long transportTimeoutMs,
            long initializationTimeoutMs,
            int maxToolResultCharacters,
            Service mattermost,
            Service gitea,
            PlaneService plane
    ) implements AgentIdentityConfig.Mcp {
    }

    record TestLoggingConfig() implements AgentIdentityConfig.Mcp.Logging {
        @Override public boolean enabled() { return false; }
        @Override public boolean transportRequests() { return false; }
        @Override public boolean transportResponses() { return false; }
    }

    record TestServiceConfig(
            String urlValue,
            String transportType,
            Optional<String> authHeaderName,
            Optional<String> authHeaderValueFormat
    ) implements AgentIdentityConfig.Mcp.Service {
        @Override public Optional<String> url() { return Optional.ofNullable(urlValue); }
    }

    record TestPlaneConfig(
            String urlValue,
            String transportType,
            Optional<String> authHeaderName,
            Optional<String> authHeaderValueFormat,
            Optional<String> workspaceSlug
    ) implements AgentIdentityConfig.Mcp.PlaneService {
        @Override public Optional<String> url() { return Optional.ofNullable(urlValue); }
    }
}
