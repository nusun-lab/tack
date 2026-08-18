package us.nusun.llmshare.framework.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.mcp.client.*;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.nusun.llmshare.framework.logging.LogTokens;
import us.nusun.llmshare.framework.memory.ApproximateTokenCountEstimator;
import us.nusun.llmshare.framework.model.Persona;
import us.nusun.llmshare.framework.model.ToolProfile;

import java.util.List;
import java.util.Map;

public class ProfiledMcpClient implements McpClient {
    private static final Logger LOG = LoggerFactory.getLogger(ProfiledMcpClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_LOG_VALUE_LENGTH = 4000;
    private static final String RESERVED_TERMINAL_TOOL = "finish_act";

    private final McpClient delegate;
    private final String server;
    private final ToolProfile profile;
    private final Persona persona;
    private final LogTokens logTokens;
    private final int globalMaxResultTokens;
    private final TokenCountEstimator tokenCountEstimator;

    public ProfiledMcpClient(McpClient delegate, String server, ToolProfile profile) {
        this(delegate, server, profile, null, null, McpToolResultLimiter.DISABLED);
    }

    public ProfiledMcpClient(McpClient delegate, String server, ToolProfile profile, Persona persona, LogTokens logTokens, int globalMaxResultTokens) {
        this(delegate, server, profile, persona, logTokens, globalMaxResultTokens, new ApproximateTokenCountEstimator());
    }

    public ProfiledMcpClient(McpClient delegate, String server, ToolProfile profile, Persona persona, LogTokens logTokens, int globalMaxResultTokens, TokenCountEstimator tokenCountEstimator) {
        this.delegate = delegate;
        this.server = server;
        this.profile = profile;
        this.persona = persona;
        this.logTokens = logTokens;
        this.globalMaxResultTokens = globalMaxResultTokens;
        this.tokenCountEstimator = tokenCountEstimator == null ? new ApproximateTokenCountEstimator() : tokenCountEstimator;
    }

    @Override public String key() { return delegate.key(); }
    @Override public List<ToolSpecification> listTools() { return filterAndLog(delegate.listTools()); }
    @Override public List<ToolSpecification> listTools(InvocationContext invocationContext) { return filterAndLog(delegate.listTools(invocationContext)); }
    @Override public ToolExecutionResult executeTool(ToolExecutionRequest executionRequest) { return executeToolInternal(executionRequest, null); }
    @Override public ToolExecutionResult executeTool(ToolExecutionRequest executionRequest, InvocationContext invocationContext) { return executeToolInternal(executionRequest, invocationContext); }
    @Override public List<McpResource> listResources() { return delegate.listResources(); }
    @Override public List<McpResource> listResources(InvocationContext invocationContext) { return delegate.listResources(invocationContext); }
    @Override public List<McpResourceTemplate> listResourceTemplates() { return delegate.listResourceTemplates(); }
    @Override public List<McpResourceTemplate> listResourceTemplates(InvocationContext invocationContext) { return delegate.listResourceTemplates(invocationContext); }
    @Override public McpReadResourceResult readResource(String uri) { return delegate.readResource(uri); }
    @Override public McpReadResourceResult readResource(String uri, InvocationContext invocationContext) { return delegate.readResource(uri, invocationContext); }
    @Override public void subscribeToResource(String uri) { delegate.subscribeToResource(uri); }
    @Override public void unsubscribeFromResource(String uri) { delegate.unsubscribeFromResource(uri); }
    @Override public List<McpPrompt> listPrompts() { return delegate.listPrompts(); }
    @Override public McpGetPromptResult getPrompt(String name, Map<String, Object> arguments) { return delegate.getPrompt(name, arguments); }
    @Override public void checkHealth() { delegate.checkHealth(); }
    @Override public void setRoots(List<McpRoot> roots) { delegate.setRoots(roots); }
    @Override public void close() throws Exception { delegate.close(); }

    private List<ToolSpecification> filterAndLog(List<ToolSpecification> tools) {
        List<ToolSpecification> filtered = filter(tools);
        LOG.info("MCP tools discovered server={} profile={} persona={} count={} tools={}",
                server,
                profile.id(),
                persona == null ? null : persona.id(),
                filtered.size(),
                filtered.stream().map(ToolSpecification::name).toList());
        return filtered;
    }

    private List<ToolSpecification> filter(List<ToolSpecification> tools) {
        if (tools == null) {
            return List.of();
        }
        return tools.stream()
                .filter(tool -> profile.allowsTool(server, tool.name()))
                .map(this::exposeTool)
                .toList();
    }

    private ToolSpecification exposeTool(ToolSpecification tool) {
        if (RESERVED_TERMINAL_TOOL.equals(tool.name())) {
            throw new IllegalStateException("MCP tool name 'finish_act' is reserved by Tack");
        }
        if (!qualifiesToolNames()) {
            return tool;
        }
        return tool.toBuilder().name(exposedToolName(tool.name())).build();
    }

    private void checkAllowed(String exposedToolName) {
        String toolName = originalToolName(exposedToolName);
        if (!profile.allowsTool(server, toolName)) {
            throw new IllegalArgumentException("Tool " + exposedToolName + " is not allowed by profile " + profile.id());
        }
    }

    public ToolExecutionRequest applyProfile(ToolExecutionRequest request) {
        String toolName = originalToolName(request.name());
        ToolExecutionRequest rawRequest = toolName.equals(request.name()) ? request : request.toBuilder().name(toolName).build();
        Map<String, JsonNode> defaults = profile.defaultArguments(server, toolName);
        if (defaults.isEmpty()) {
            return rawRequest;
        }
        ObjectNode arguments = parseArgumentsObject(rawRequest.arguments());
        defaults.forEach((name, value) -> {
            if (!arguments.has(name)) {
                arguments.set(name, value == null ? OBJECT_MAPPER.nullNode() : value.deepCopy());
            }
        });
        return rawRequest.toBuilder().arguments(arguments.toString()).build();
    }

    private ToolExecutionResult executeToolInternal(ToolExecutionRequest request, InvocationContext invocationContext) {
        checkAllowed(request.name());
        ToolExecutionRequest profiled = applyProfile(request);
        logDim("TOOL " + server + "." + profiled.name() + " REQUEST " + abbreviate(profiled.arguments()));
        try {
            ToolExecutionResult result = invocationContext == null
                    ? delegate.executeTool(profiled)
                    : delegate.executeTool(profiled, invocationContext);
            result = limitResult(result);
            logDim("TOOL " + server + "." + profiled.name() + " RESPONSE isError=" + result.isError() + " " + abbreviate(resultText(result)));
            return result;
        } catch (RuntimeException e) {
            logDim("TOOL " + server + "." + profiled.name() + " ERROR " + abbreviate(e.getMessage()));
            throw e;
        }
    }

    private boolean qualifiesToolNames() {
        return profile.mcpServers().size() > 1;
    }

    private String exposedToolName(String toolName) {
        return serverQualifier() + "__" + toolName;
    }

    private String originalToolName(String toolName) {
        if (!qualifiesToolNames()) {
            return toolName;
        }
        String prefix = serverQualifier() + "__";
        return toolName != null && toolName.startsWith(prefix) ? toolName.substring(prefix.length()) : toolName;
    }

    private String serverQualifier() {
        return server.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private ToolExecutionResult limitResult(ToolExecutionResult result) {
        return McpToolResultLimiter.limit(
                result,
                McpToolResultLimiter.effectiveLimit(profile.maxResultTokens(), globalMaxResultTokens),
                tokenCountEstimator);
    }

    private void logDim(String message) {
        if (persona == null || logTokens == null) {
            return;
        }
        logTokens.print(logTokens.wrapDim(label(), persona.color(), message));
    }

    private String label() {
        if (persona.label() != null && !persona.label().isBlank()) {
            return persona.label();
        }
        return persona.name();
    }

    private static String resultText(ToolExecutionResult result) {
        return McpToolResultLimiter.resultText(result);
    }

    private static String abbreviate(Object value) {
        if (value == null) {
            return "null";
        }
        String text = String.valueOf(value);
        return text.length() <= MAX_LOG_VALUE_LENGTH ? text : text.substring(0, MAX_LOG_VALUE_LENGTH) + "...(truncated)";
    }

    private static ObjectNode parseArgumentsObject(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return OBJECT_MAPPER.createObjectNode();
        }
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(arguments);
            return parsed instanceof ObjectNode objectNode ? objectNode : OBJECT_MAPPER.createObjectNode();
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to parse MCP tool arguments as JSON", e);
        }
    }
}
