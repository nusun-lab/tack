package us.nusun.llmshare.mcp;

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
import us.nusun.llmshare.bean.LogTokens;

import java.util.List;
import java.util.Map;

final class LoggingMcpClient implements McpClient {
    private static final int MAX_LOG_VALUE_LENGTH = 4000;

    private final McpClient delegate;
    private final LogTokens logTokens;
    private final String agentLabel;
    private final String messagePrefix;

    LoggingMcpClient(McpClient delegate, String serviceName, String identityKey, String agentLabel, LogTokens logTokens) {
        this.delegate = delegate;
        this.logTokens = logTokens;
        this.agentLabel = agentLabel;
        this.messagePrefix = "MCP " + serviceName + "[" + identityKey + "]";
    }

    @Override
    public String key() {
        return delegate.key();
    }

    @Override
    public List<ToolSpecification> listTools() {
        logDim("listTools request");
        List<ToolSpecification> tools = delegate.listTools();
        logDim("listTools response count=" + tools.size());
        return tools;
    }

    @Override
    public List<ToolSpecification> listTools(InvocationContext invocationContext) {
        logDim("listTools request");
        List<ToolSpecification> tools = delegate.listTools(invocationContext);
        logDim("listTools response count=" + tools.size());
        return tools;
    }

    @Override
    public ToolExecutionResult executeTool(ToolExecutionRequest executionRequest) {
        return executeToolInternal(executionRequest, null);
    }

    @Override
    public ToolExecutionResult executeTool(ToolExecutionRequest executionRequest, InvocationContext invocationContext) {
        return executeToolInternal(executionRequest, invocationContext);
    }

    @Override
    public List<McpResource> listResources() {
        logDim("listResources request");
        List<McpResource> resources = delegate.listResources();
        logDim("listResources response count=" + resources.size());
        return resources;
    }

    @Override
    public List<McpResource> listResources(InvocationContext invocationContext) {
        logDim("listResources request");
        List<McpResource> resources = delegate.listResources(invocationContext);
        logDim("listResources response count=" + resources.size());
        return resources;
    }

    @Override
    public List<McpResourceTemplate> listResourceTemplates() {
        logDim("listResourceTemplates request");
        List<McpResourceTemplate> templates = delegate.listResourceTemplates();
        logDim("listResourceTemplates response count=" + templates.size());
        return templates;
    }

    @Override
    public List<McpResourceTemplate> listResourceTemplates(InvocationContext invocationContext) {
        logDim("listResourceTemplates request");
        List<McpResourceTemplate> templates = delegate.listResourceTemplates(invocationContext);
        logDim("listResourceTemplates response count=" + templates.size());
        return templates;
    }

    @Override
    public McpReadResourceResult readResource(String uri) {
        logDim("readResource request uri=" + uri);
        McpReadResourceResult result = delegate.readResource(uri);
        logDim("readResource response uri=" + uri + " contents=" + abbreviate(String.valueOf(result)));
        return result;
    }

    @Override
    public McpReadResourceResult readResource(String uri, InvocationContext invocationContext) {
        logDim("readResource request uri=" + uri);
        McpReadResourceResult result = delegate.readResource(uri, invocationContext);
        logDim("readResource response uri=" + uri + " contents=" + abbreviate(String.valueOf(result)));
        return result;
    }

    @Override
    public void subscribeToResource(String uri) {
        logDim("subscribeToResource uri=" + uri);
        delegate.subscribeToResource(uri);
    }

    @Override
    public void unsubscribeFromResource(String uri) {
        logDim("unsubscribeFromResource uri=" + uri);
        delegate.unsubscribeFromResource(uri);
    }

    @Override
    public List<McpPrompt> listPrompts() {
        logDim("listPrompts request");
        List<McpPrompt> prompts = delegate.listPrompts();
        logDim("listPrompts response count=" + prompts.size());
        return prompts;
    }

    @Override
    public McpGetPromptResult getPrompt(String name, Map<String, Object> arguments) {
        logDim("getPrompt request name=" + name + " args=" + abbreviate(String.valueOf(arguments)));
        McpGetPromptResult result = delegate.getPrompt(name, arguments);
        logDim("getPrompt response name=" + name + " result=" + abbreviate(String.valueOf(result)));
        return result;
    }

    @Override
    public void checkHealth() {
        logDim("checkHealth request");
        delegate.checkHealth();
        logDim("checkHealth response ok");
    }

    @Override
    public void setRoots(List<McpRoot> roots) {
        logDim("setRoots count=" + (roots == null ? 0 : roots.size()));
        delegate.setRoots(roots);
    }

    @Override
    public void close() throws Exception {
        delegate.close();
    }

    private ToolExecutionResult executeToolInternal(ToolExecutionRequest executionRequest, InvocationContext invocationContext) {
        logDim("tool request name=" + executionRequest.name()
                + " args=" + abbreviate(executionRequest.arguments()));
        ToolExecutionResult result = invocationContext == null
                ? delegate.executeTool(executionRequest)
                : delegate.executeTool(executionRequest, invocationContext);
        logDim("tool response name=" + executionRequest.name()
                + " isError=" + result.isError()
                + " result=" + abbreviate(resultText(result)));
        return result;
    }

    private static String resultText(ToolExecutionResult result) {
        try {
            return result.resultText();
        } catch (Exception ignored) {
            return String.valueOf(result);
        }
    }

    private void logDim(String message) {
        logTokens.print(logTokens.wrapDim(agentLabel, messagePrefix + " " + message));
    }

    private static String abbreviate(Object value) {
        if (value == null) {
            return "null";
        }
        String text = String.valueOf(value);
        return text.length() <= MAX_LOG_VALUE_LENGTH ? text : text.substring(0, MAX_LOG_VALUE_LENGTH) + "...(truncated)";
    }
}
