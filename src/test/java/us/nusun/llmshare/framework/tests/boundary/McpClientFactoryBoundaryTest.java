package us.nusun.llmshare.framework.tests.boundary;

import dev.langchain4j.mcp.client.McpClient;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.logging.LogTokens;
import us.nusun.llmshare.framework.mcp.McpClientFactory;
import us.nusun.llmshare.framework.model.McpServerDefinition;
import us.nusun.llmshare.framework.model.Persona;
import us.nusun.llmshare.framework.model.ToolProfile;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;

import java.util.List;

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
        McpClientFactory factory = factory();
        Persona persona = Persona.builder("persona")
                .name("Persona")
                .identity("plane", "", "@persona")
                .identity("gitea", "", "@persona")
                .build();
        ToolProfile profile = ToolProfile.builder("tools")
                .mcpServer(server("plane", null))
                .mcpServer(server("gitea", "http://gitea.example"))
                .allowAllTools("plane")
                .allowAllTools("gitea")
                .build();

        assertEquals(List.of(), factory.clientsFor(persona, profile));
    }

    @Test
    void createsOnlyAllowedServerClientsWithPerPersonaLazyKeys() {
        McpClientFactory factory = factory();
        Persona persona = Persona.builder("persona-a")
                .name("Persona A")
                .identity("plane", "plane-secret", "@miles-agent")
                .identity("gitea", "", "@miles-agent")
                .build();
        ToolProfile profile = ToolProfile.builder("project-management")
                .mcpServer(server("plane", "http://plane.example/mcp"))
                .mcpServer(server("gitea", "http://gitea.example/mcp"))
                .allowAllTools("plane")
                .build();

        List<McpClient> clients = factory.clientsFor(persona, profile);

        assertEquals(1, clients.size());
        assertEquals("plane:persona-a", clients.getFirst().key());
    }

    @Test
    void factoryBuildsCustomServerDefinitionWithoutCodeChanges() {
        McpClientFactory factory = factory();
        Persona persona = Persona.builder("persona-a")
                .name("Persona A")
                .identity("custom", "custom-secret", "@custom-agent")
                .build();
        ToolProfile profile = ToolProfile.builder("custom-tools")
                .mcpServer(McpServerDefinition.builder("custom")
                        .url("http://custom.example/mcp")
                        .authHeaderName("X-Token")
                        .authHeaderValueFormat("Token %s")
                        .build())
                .allowAllTools("custom")
                .build();

        List<McpClient> clients = factory.clientsFor(persona, profile);

        assertEquals(1, clients.size());
        assertEquals("custom:persona-a", clients.getFirst().key());
    }

    @Test
    void toolProfileFilteringStillWorksByServerId() {
        McpClientFactory factory = factory();
        Persona persona = Persona.builder("persona-a")
                .name("Persona A")
                .identity("plane", "plane-secret", "@plane-agent")
                .identity("custom", "custom-secret", "@custom-agent")
                .build();
        ToolProfile profile = ToolProfile.builder("project-management")
                .mcpServer(server("plane", "http://plane.example/mcp"))
                .allowAllTools("plane")
                .build();

        List<McpClient> clients = factory.clientsFor(persona, profile);

        assertEquals(List.of("plane:persona-a"), clients.stream().map(McpClient::key).toList());
    }

    @Test
    void profileServerDefinitionUsesConcreteRuntimeUrl() {
        ToolProfile profile = ToolProfile.builder("source-control")
                .mcpServer(McpServerDefinition.builder("gitea")
                        .url("http://gitea.example/mcp")
                        .authHeaderValueFormat("token %s")
                        .build())
                .allowAllTools("gitea")
                .build();

        McpServerDefinition server = profile.mcpServers().get("gitea");

        assertEquals("gitea", server.id());
        assertEquals("http://gitea.example/mcp", server.url());
        assertEquals("token %s", server.authHeaderValueFormat());
    }

    private static McpClientFactory factory() {
        McpClientFactory factory = new McpClientFactory();
        FrameworkFixtures.set(factory, "transportTimeoutMs", 1000L);
        FrameworkFixtures.set(factory, "initializationTimeoutMs", 500L);
        FrameworkFixtures.set(factory, "maxToolResultCharacters", 1000);
        FrameworkFixtures.set(factory, "logTransportRequests", false);
        FrameworkFixtures.set(factory, "logTransportResponses", false);
        FrameworkFixtures.set(factory, "logTokens", new LogTokens(false));
        return factory;
    }

    private static McpServerDefinition server(String id, String url) {
        return McpServerDefinition.builder(id).url(url == null ? "" : url).build();
    }
}
