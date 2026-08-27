package us.nusun.llmshare.framework.tests.boundary;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpGetPromptResult;
import dev.langchain4j.mcp.client.McpPrompt;
import dev.langchain4j.mcp.client.McpReadResourceResult;
import dev.langchain4j.mcp.client.McpResource;
import dev.langchain4j.mcp.client.McpResourceTemplate;
import dev.langchain4j.mcp.client.McpRoot;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.mcp.LazyInitializingMcpClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LazyInitializingMcpClientBoundaryTest {
    @Test
    void initializesDelegateOnlyOnceAcrossRepeatedOperations() {
        AtomicInteger creations = new AtomicInteger();
        LazyInitializingMcpClient client = lazy(() -> {
            creations.incrementAndGet();
            return new FakeMcpClient();
        });

        assertEquals("lazy-key", client.key());
        assertEquals(List.of("allowed_tool"), client.listTools().stream().map(ToolSpecification::name).toList());
        client.checkHealth();
        client.executeTool(ToolExecutionRequest.builder().id("1").name("allowed_tool").arguments("{}").build());

        assertEquals(1, creations.get());
    }

    @Test
    void initializationFailureIsWrappedWithServiceIdentityUrlAndRootCause() {
        LazyInitializingMcpClient client = lazy(() -> {
            throw new IllegalArgumentException("connection refused");
        });

        IllegalStateException failure = assertThrows(IllegalStateException.class, client::listTools);

        assertTrue(failure.getMessage().contains("service 'synthetic-mcp'"));
        assertTrue(failure.getMessage().contains("identity '@miles'"));
        assertTrue(failure.getMessage().contains("url 'http://mcp.example'"));
        assertTrue(failure.getMessage().contains("connection refused"));
    }

    @Test
    void failedInitializationIsRetriedOnNextOperation() {
        AtomicInteger attempts = new AtomicInteger();
        LazyInitializingMcpClient client = lazy(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("first failure");
            }
            return new FakeMcpClient();
        });

        assertThrows(IllegalStateException.class, client::listTools);
        assertEquals(List.of("allowed_tool"), client.listTools().stream().map(ToolSpecification::name).toList());
        assertEquals(2, attempts.get());
    }

    @Test
    void closeIsIdempotentAndPreventsReinitialization() throws Exception {
        AtomicInteger creations = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        LazyInitializingMcpClient client = lazy(() -> {
            creations.incrementAndGet();
            return new FakeMcpClient(closes);
        });

        client.listTools();
        client.close();
        client.close();

        assertEquals(1, creations.get());
        assertEquals(1, closes.get());
        IllegalStateException failure = assertThrows(IllegalStateException.class, client::listTools);
        assertTrue(failure.getMessage().contains("is closed"));
        assertEquals(1, creations.get());
    }

    @Test
    void closeBeforeInitializationDoesNotCreateDelegate() throws Exception {
        AtomicInteger creations = new AtomicInteger();
        LazyInitializingMcpClient client = lazy(() -> {
            creations.incrementAndGet();
            return new FakeMcpClient();
        });

        client.close();

        assertThrows(IllegalStateException.class, client::listTools);
        assertEquals(0, creations.get());
    }

    private static LazyInitializingMcpClient lazy(java.util.function.Supplier<McpClient> supplier) {
        return new LazyInitializingMcpClient(
                "lazy-key",
                "synthetic-mcp",
                "@miles",
                "streamableHttp",
                "http://mcp.example",
                1000,
                500,
                supplier);
    }

    static class FakeMcpClient implements McpClient {
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
