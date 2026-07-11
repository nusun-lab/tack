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
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McpClientFactoryBoundaryTest {
    @Test
    void noneProfileReturnsNoClientsWithoutReadingServerConfig() {
        McpClientFactory factory = new McpClientFactory();

        assertEquals(List.of(), factory.clientsFor(FrameworkFixtures.personas().getFirst(), ToolProfile.builder("none").build()));
    }

    @Test
    void createsOneProfiledClientForEachConfiguredServerWithCredentials() {
        McpClientFactory factory = factory();
        Persona persona = Persona.builder("persona-a")
                .name("Persona A")
                .identity(FrameworkFixtures.SERVER_ALPHA, "alpha-secret", "@alpha-agent")
                .identity(FrameworkFixtures.SERVER_BETA, "beta-secret", "@beta-agent")
                .build();
        ToolProfile profile = ToolProfile.builder("synthetic-tools")
                .mcpServer(server(FrameworkFixtures.SERVER_ALPHA, "http://alpha.example/mcp"))
                .mcpServer(server(FrameworkFixtures.SERVER_BETA, "http://beta.example/mcp"))
                .allowAllTools(FrameworkFixtures.SERVER_ALPHA)
                .allowAllTools(FrameworkFixtures.SERVER_BETA)
                .build();

        List<McpClient> clients = factory.clientsFor(persona, profile);

        assertEquals(Set.of(
                        FrameworkFixtures.SERVER_ALPHA + ":persona-a",
                        FrameworkFixtures.SERVER_BETA + ":persona-a"),
                clients.stream().map(McpClient::key).collect(Collectors.toSet()));
    }

    @Test
    void missingUrlOrTokenSkipsOnlyThatServerClient() {
        McpClientFactory factory = factory();
        Persona persona = Persona.builder("persona-a")
                .name("Persona A")
                .identity(FrameworkFixtures.SERVER_ALPHA, "alpha-secret", "@alpha-agent")
                .identity(FrameworkFixtures.SERVER_BETA, "", "@beta-agent")
                .identity(FrameworkFixtures.SERVER_GAMMA, "gamma-secret", "@gamma-agent")
                .build();
        ToolProfile profile = ToolProfile.builder("synthetic-tools")
                .mcpServer(server(FrameworkFixtures.SERVER_ALPHA, "http://alpha.example/mcp"))
                .mcpServer(server(FrameworkFixtures.SERVER_BETA, "http://beta.example/mcp"))
                .mcpServer(server(FrameworkFixtures.SERVER_GAMMA, ""))
                .allowAllTools(FrameworkFixtures.SERVER_ALPHA)
                .allowAllTools(FrameworkFixtures.SERVER_BETA)
                .allowAllTools(FrameworkFixtures.SERVER_GAMMA)
                .build();

        List<McpClient> clients = factory.clientsFor(persona, profile);

        assertEquals(List.of(FrameworkFixtures.SERVER_ALPHA + ":persona-a"), clients.stream().map(McpClient::key).toList());
    }

    @Test
    void factoryBuildsCustomServerDefinitionWithoutCodeChanges() {
        McpClientFactory factory = factory();
        Persona persona = Persona.builder("persona-a")
                .name("Persona A")
                .identity("custom-mcp", "custom-secret", "@custom-agent")
                .build();
        ToolProfile profile = ToolProfile.builder("custom-tools")
                .mcpServer(McpServerDefinition.builder("custom-mcp")
                        .url("http://custom.example/mcp")
                        .authHeaderName("X-Token")
                        .authHeaderValueFormat("Token %s")
                        .build())
                .allowAllTools("custom-mcp")
                .build();

        List<McpClient> clients = factory.clientsFor(persona, profile);

        assertEquals(1, clients.size());
        assertEquals("custom-mcp:persona-a", clients.getFirst().key());
    }

    @Test
    void toolProfileFilteringStillWorksByServerId() {
        McpClientFactory factory = factory();
        Persona persona = Persona.builder("persona-a")
                .name("Persona A")
                .identity(FrameworkFixtures.SERVER_ALPHA, "alpha-secret", "@alpha-agent")
                .identity("custom-mcp", "custom-secret", "@custom-agent")
                .build();
        ToolProfile profile = ToolProfile.builder("synthetic-tools")
                .mcpServer(server(FrameworkFixtures.SERVER_ALPHA, "http://alpha.example/mcp"))
                .allowAllTools(FrameworkFixtures.SERVER_ALPHA)
                .build();

        List<McpClient> clients = factory.clientsFor(persona, profile);

        assertEquals(List.of(FrameworkFixtures.SERVER_ALPHA + ":persona-a"), clients.stream().map(McpClient::key).toList());
    }

    private static McpClientFactory factory() {
        McpClientFactory factory = new McpClientFactory();
        FrameworkFixtures.set(factory, "transportTimeoutMs", 1000L);
        FrameworkFixtures.set(factory, "initializationTimeoutMs", 500L);
        FrameworkFixtures.set(factory, "maxToolResultTokens", 1000);
        FrameworkFixtures.set(factory, "logTransportRequests", false);
        FrameworkFixtures.set(factory, "logTransportResponses", false);
        FrameworkFixtures.set(factory, "logTokens", new LogTokens(false));
        return factory;
    }

    private static McpServerDefinition server(String id, String url) {
        return McpServerDefinition.builder(id).url(url == null ? "" : url).build();
    }
}
