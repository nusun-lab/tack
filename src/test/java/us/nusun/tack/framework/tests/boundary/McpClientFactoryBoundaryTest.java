package us.nusun.tack.framework.tests.boundary;

import dev.langchain4j.mcp.client.McpClient;
import org.junit.jupiter.api.Test;
import us.nusun.tack.framework.logging.LogTokens;
import us.nusun.tack.framework.mcp.McpClientFactory;
import us.nusun.tack.framework.model.McpServerDefinition;
import us.nusun.tack.framework.model.Persona;
import us.nusun.tack.framework.model.ToolProfile;
import us.nusun.tack.framework.tests.support.FrameworkFixtures;

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
    void reusesCachedClientAcrossProfilesForSamePersonaAndConnection() {
        McpClientFactory factory = factory();
        Persona persona = Persona.builder("persona-a")
                .name("Persona A")
                .identity(FrameworkFixtures.SERVER_ALPHA, "alpha-secret", "@alpha-agent")
                .build();
        McpServerDefinition sharedServer = server(FrameworkFixtures.SERVER_ALPHA, "http://alpha.example/mcp");
        ToolProfile readProfile = ToolProfile.builder("read-tools")
                .mcpServer(sharedServer)
                .allowTool(FrameworkFixtures.SERVER_ALPHA, "alpha_get")
                .build();
        ToolProfile writeProfile = ToolProfile.builder("write-tools")
                .mcpServer(sharedServer)
                .allowTool(FrameworkFixtures.SERVER_ALPHA, "alpha_post")
                .build();

        List<McpClient> readClients = factory.clientsFor(persona, readProfile);
        List<McpClient> writeClients = factory.clientsFor(persona, writeProfile);

        assertEquals(1, readClients.size());
        assertEquals(1, writeClients.size());
        assertEquals(readClients.getFirst().key(), writeClients.getFirst().key());
        assertEquals(1, factory.cachedClientCount());
        assertEquals(1, factory.cacheMisses());
        assertEquals(1, factory.cacheHits());
    }

    @Test
    void separatesCachedClientsForDifferentPersonaCredentials() {
        McpClientFactory factory = factory();
        Persona personaA = Persona.builder("persona-a")
                .name("Persona A")
                .identity(FrameworkFixtures.SERVER_ALPHA, "alpha-secret-a", "@alpha-a")
                .build();
        Persona personaB = Persona.builder("persona-b")
                .name("Persona B")
                .identity(FrameworkFixtures.SERVER_ALPHA, "alpha-secret-b", "@alpha-b")
                .build();
        ToolProfile profile = ToolProfile.builder("shared-tools")
                .mcpServer(server(FrameworkFixtures.SERVER_ALPHA, "http://alpha.example/mcp"))
                .allowAllTools(FrameworkFixtures.SERVER_ALPHA)
                .build();

        factory.clientsFor(personaA, profile);
        factory.clientsFor(personaB, profile);

        assertEquals(2, factory.cachedClientCount());
        assertEquals(2, factory.cacheMisses());
        assertEquals(0, factory.cacheHits());
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
