package us.nusun.tack.framework.tool.mcp;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.mcp.client.*;
import dev.langchain4j.service.IllegalConfigurationException;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import org.junit.jupiter.api.Test;
import us.nusun.tack.framework.tool.McpServerDefinition;
import us.nusun.tack.framework.persona.Persona;
import us.nusun.tack.framework.tool.ToolProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class McpClientFactoryTest {
    private static final ToolProviderRequest REQUEST = new ToolProviderRequest("memory", UserMessage.from("Inspect"));

    @Test
    void discoveryIsLazyAndSharesOneConnectionUnderContentionAcrossProfiles() throws Exception {
        FakeFactory factory = new FakeFactory();
        Persona persona = persona("first", "first-token");
        String url = "http://mcp.example";
        ToolProvider first = factory.toolProvidersFor(persona, profile("read",
                McpServerDefinition.builder("mcp").url(url).build())).getFirst();
        ToolProvider second = factory.toolProvidersFor(persona, profile("write",
                new McpServerDefinition("mcp", url, " ", null, "", Map.of()))).getFirst();
        assertEquals(0, factory.attempts.get());

        try (var threads = Executors.newFixedThreadPool(4)) {
            List<Future<Integer>> discoveries = new ArrayList<>();
            for (ToolProvider provider : List.of(first, second, first, second)) {
                discoveries.add(threads.submit(() -> provider.provideTools(REQUEST).aiServiceTools().size()));
            }
            for (var discovery : discoveries) {
                assertEquals(1, discovery.get());
            }
        }
        assertEquals(1, factory.attempts.get());
        assertEquals(List.of("Bearer first-token"), factory.authorization);
        assertEquals(0, factory.closes.get());

        factory.closeCachedClients();
        factory.closeCachedClients();
        assertEquals(1, factory.closes.get());
        assertTrue(first.provideTools(REQUEST).aiServiceTools().isEmpty());
        assertThrows(IllegalStateException.class, () -> factory.toolProvidersFor(persona, profile("read")));
        assertEquals(1, factory.attempts.get());
    }

    @Test
    void skipsUnavailableConnectionAndRetriesAtNextDiscovery() {
        FakeFactory factory = new FakeFactory();
        factory.failure = new IllegalStateException("connection unavailable");
        ToolProvider provider = factory.toolProvidersFor(persona("first", "token"), profile("read")).getFirst();

        assertTrue(provider.provideTools(REQUEST).aiServiceTools().isEmpty());
        assertEquals(1, provider.provideTools(REQUEST).aiServiceTools().size());
        assertEquals(2, factory.attempts.get());
    }

    @Test
    void configurationErrorsPropagate() {
        FakeFactory factory = new FakeFactory();
        factory.failure = new IllegalConfigurationException("invalid connection");
        ToolProvider provider = factory.toolProvidersFor(persona("first", "token"), profile("read")).getFirst();

        assertThrows(IllegalConfigurationException.class, () -> provider.provideTools(REQUEST));
    }

    @Test
    void closesUnusedConnectionsWithoutOpeningThem() {
        FakeFactory factory = new FakeFactory();
        ToolProvider provider = factory.toolProvidersFor(persona("first", "token"), profile("read")).getFirst();
        factory.closeCachedClients();

        assertTrue(provider.provideTools(REQUEST).aiServiceTools().isEmpty());
        assertEquals(0, factory.attempts.get());
        assertEquals(0, factory.closes.get());
    }

    @Test
    void separatesPersonaCredentialsAndSkipsMissingCredentials() {
        FakeFactory factory = new FakeFactory();
        ToolProfile profile = profile("read");
        factory.toolProvidersFor(persona("first", "first-token"), profile).getFirst().provideTools(REQUEST);
        factory.toolProvidersFor(persona("second", "second-token"), profile).getFirst().provideTools(REQUEST);

        assertTrue(factory.toolProvidersFor(persona("missing", ""), profile).isEmpty());
        assertEquals(List.of("Token first-token", "Token second-token"), factory.authorization);
        assertEquals(2, factory.attempts.get());
        factory.closeCachedClients();
        assertEquals(2, factory.closes.get());
    }

    private static Persona persona(String id, String token) {
        return Persona.builder(id).name(id).identity("mcp", token, id).build();
    }

    private static ToolProfile profile(String id) {
        return profile(id, McpServerDefinition.builder("mcp")
                .url("http://mcp.example").authHeaderName("X-Token").authHeaderValueFormat("Token %s").build());
    }

    private static ToolProfile profile(String id, McpServerDefinition server) {
        return ToolProfile.builder(id).mcpServer(server).allowTool("mcp", "allowed_tool").build();
    }

    private static class FakeFactory extends McpClientFactory {
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final List<String> authorization = new ArrayList<>();
        RuntimeException failure;

        @Override
        McpClient connect(String key, String url, String transportType, Map<String, String> headers) {
            attempts.incrementAndGet();
            if (failure != null) {
                var next = failure;
                failure = null;
                throw next;
            }
            authorization.add(headers.getOrDefault("X-Token", headers.get("Authorization")));
            return new FakeMcpClient(closes);
        }
    }

    private static class FakeMcpClient implements McpClient {
        private final AtomicInteger closes;

        FakeMcpClient() {
            this(new AtomicInteger());
        }

        FakeMcpClient(AtomicInteger closes) {
            this.closes = closes;
        }

        @Override public String key() { return "delegate-key"; }
        @Override public List<ToolSpecification> listTools() { return List.of(ToolSpecification.builder().name("allowed_tool").description("Allowed").build()); }
        @Override public List<ToolSpecification> listTools(InvocationContext invocationContext) { return listTools(); }
        @Override public ToolExecutionResult executeTool(ToolExecutionRequest executionRequest) { return ToolExecutionResult.builder().isError(false).resultText("ok").build(); }
        @Override public ToolExecutionResult executeTool(ToolExecutionRequest executionRequest, InvocationContext invocationContext) { return executeTool(executionRequest); }
        @Override public List<McpResource> listResources() { return List.of(); }
        @Override public List<McpResource> listResources(InvocationContext invocationContext) { return List.of(); }
        @Override public List<McpResourceTemplate> listResourceTemplates() { return List.of(); }
        @Override public List<McpResourceTemplate> listResourceTemplates(InvocationContext invocationContext) { return List.of(); }
        @Override public McpReadResourceResult readResource(String uri) { return null; }
        @Override public McpReadResourceResult readResource(String uri, InvocationContext invocationContext) { return null; }
        @Override public void subscribeToResource(String uri) {}
        @Override public void unsubscribeFromResource(String uri) {}
        @Override public List<McpPrompt> listPrompts() { return List.of(); }
        @Override public McpGetPromptResult getPrompt(String name, Map<String, Object> arguments) { return null; }
        @Override public void checkHealth() {}
        @Override public void setRoots(List<McpRoot> roots) {}
        @Override public void close() { closes.incrementAndGet(); }
    }
}
