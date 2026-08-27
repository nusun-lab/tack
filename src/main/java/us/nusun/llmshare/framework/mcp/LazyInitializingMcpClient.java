package us.nusun.llmshare.framework.mcp;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class LazyInitializingMcpClient implements McpClient {
    private static final Logger LOG = LoggerFactory.getLogger(LazyInitializingMcpClient.class);

    private final String key;
    private final String serviceName;
    private final String identityKey;
    private final String transportType;
    private final String url;
    private final long transportTimeoutMs;
    private final long initializationTimeoutMs;
    private final Supplier<McpClient> supplier;

    private volatile McpClient delegate;
    private volatile boolean closed;
    private final Object lock = new Object();

    public LazyInitializingMcpClient(
            String key,
            String serviceName,
            String identityKey,
            String transportType,
            String url,
            long transportTimeoutMs,
            long initializationTimeoutMs,
            Supplier<McpClient> supplier) {
        this.key = key;
        this.serviceName = serviceName;
        this.identityKey = identityKey;
        this.transportType = transportType;
        this.url = url;
        this.transportTimeoutMs = transportTimeoutMs;
        this.initializationTimeoutMs = initializationTimeoutMs;
        this.supplier = supplier;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public List<ToolSpecification> listTools() {
        return delegate().listTools();
    }

    @Override
    public List<ToolSpecification> listTools(InvocationContext invocationContext) {
        return delegate().listTools(invocationContext);
    }

    @Override
    public ToolExecutionResult executeTool(ToolExecutionRequest executionRequest) {
        return delegate().executeTool(executionRequest);
    }

    @Override
    public ToolExecutionResult executeTool(ToolExecutionRequest executionRequest, InvocationContext invocationContext) {
        return delegate().executeTool(executionRequest, invocationContext);
    }

    @Override
    public List<McpResource> listResources() {
        return delegate().listResources();
    }

    @Override
    public List<McpResource> listResources(InvocationContext invocationContext) {
        return delegate().listResources(invocationContext);
    }

    @Override
    public List<McpResourceTemplate> listResourceTemplates() {
        return delegate().listResourceTemplates();
    }

    @Override
    public List<McpResourceTemplate> listResourceTemplates(InvocationContext invocationContext) {
        return delegate().listResourceTemplates(invocationContext);
    }

    @Override
    public McpReadResourceResult readResource(String uri) {
        return delegate().readResource(uri);
    }

    @Override
    public McpReadResourceResult readResource(String uri, InvocationContext invocationContext) {
        return delegate().readResource(uri, invocationContext);
    }

    @Override
    public void subscribeToResource(String uri) {
        delegate().subscribeToResource(uri);
    }

    @Override
    public void unsubscribeFromResource(String uri) {
        delegate().unsubscribeFromResource(uri);
    }

    @Override
    public List<McpPrompt> listPrompts() {
        return delegate().listPrompts();
    }

    @Override
    public McpGetPromptResult getPrompt(String name, Map<String, Object> arguments) {
        return delegate().getPrompt(name, arguments);
    }

    @Override
    public void checkHealth() {
        delegate().checkHealth();
    }

    @Override
    public void setRoots(List<McpRoot> roots) {
        delegate().setRoots(roots);
    }

    @Override
    public void close() throws Exception {
        McpClient current;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            current = delegate;
            delegate = null;
        }
        if (current != null) {
            current.close();
        }
    }

    private McpClient delegate() {
        if (closed) {
            throw closedException();
        }
        McpClient current = delegate;
        if (current != null) {
            return current;
        }
        synchronized (lock) {
            if (closed) {
                throw closedException();
            }
            current = delegate;
            if (current != null) {
                return current;
            }
            LOG.info("Initializing MCP client service={} identity={} transport={} url={} transportTimeoutMs={} initializationTimeoutMs={}",
                    serviceName, identityKey, transportType, url, transportTimeoutMs, initializationTimeoutMs);
            try {
                current = supplier.get();
                delegate = current;
                LOG.info("Initialized MCP client service={} identity={} transport={} url={}",
                        serviceName, identityKey, transportType, url);
                return current;
            } catch (RuntimeException e) {
                LOG.warn("Failed to initialize MCP client service={} identity={} transport={} url={} transportTimeoutMs={} initializationTimeoutMs={} cause={}",
                        serviceName, identityKey, transportType, url, transportTimeoutMs, initializationTimeoutMs,
                        rootMessage(e));
                throw new IllegalStateException(
                        "MCP client initialization failed for service '%s' identity '%s' url '%s' transport '%s': %s"
                                .formatted(serviceName, identityKey, url, transportType, rootMessage(e)),
                        e);
            }
        }
    }

    private IllegalStateException closedException() {
        return new IllegalStateException(
                "MCP client is closed for service '%s' identity '%s' url '%s'"
                        .formatted(serviceName, identityKey, url));
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
