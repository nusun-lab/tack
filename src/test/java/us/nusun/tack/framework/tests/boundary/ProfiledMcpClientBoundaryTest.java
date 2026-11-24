package us.nusun.tack.framework.tests.boundary;

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
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.data.message.ChatMessage;
import org.junit.jupiter.api.Test;
import us.nusun.tack.framework.mcp.ProfiledMcpClient;
import us.nusun.tack.framework.model.ToolProfile;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProfiledMcpClientBoundaryTest {
    @Test
    void nonOwningProfileWrapperDoesNotCloseSharedDelegate() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        CountingMcpClient delegate = new CountingMcpClient(closes);
        ToolProfile profile = ToolProfile.builder("shared-tools")
                .allowAllTools("synthetic-mcp")
                .build();
        ProfiledMcpClient client = new ProfiledMcpClient(
                delegate,
                "synthetic-mcp",
                profile,
                null,
                null,
                1000,
                new FixedTokenCountEstimator(),
                false);

        client.close();

        assertEquals(0, closes.get());
    }

    @Test
    void owningProfileWrapperPreservesStandaloneCloseBehavior() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        CountingMcpClient delegate = new CountingMcpClient(closes);
        ToolProfile profile = ToolProfile.builder("owned-tools")
                .allowAllTools("synthetic-mcp")
                .build();
        ProfiledMcpClient client = new ProfiledMcpClient(delegate, "synthetic-mcp", profile);

        client.close();

        assertEquals(1, closes.get());
    }

    private static final class FixedTokenCountEstimator implements TokenCountEstimator {
        @Override public int estimateTokenCountInText(String text) { return text == null ? 0 : text.length(); }
        @Override public int estimateTokenCountInMessage(ChatMessage message) { return 0; }
        @Override public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) { return 0; }
    }

    private static final class CountingMcpClient implements McpClient {
        private final AtomicInteger closes;

        private CountingMcpClient(AtomicInteger closes) {
            this.closes = closes;
        }

        @Override public String key() { return "counting"; }
        @Override public List<ToolSpecification> listTools() { return List.of(); }
        @Override public List<ToolSpecification> listTools(InvocationContext invocationContext) { return List.of(); }
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
