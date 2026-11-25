package us.nusun.tack.framework.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import us.nusun.tack.framework.logging.LogTokens;
import us.nusun.tack.framework.model.Persona;
import us.nusun.tack.framework.model.ToolProfile;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class McpToolPolicy {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_LOG_VALUE_LENGTH = 4000;

    private final McpClient delegate;
    private final String server;
    private final ToolProfile profile;
    private final Persona persona;
    private final LogTokens logTokens;
    private final int globalMaxResultTokens;
    private final TokenCountEstimator tokenCountEstimator;

    public McpToolPolicy(McpClient delegate, String server, ToolProfile profile, Persona persona,
                         LogTokens logTokens, int globalMaxResultTokens, TokenCountEstimator tokenCountEstimator) {
        this.delegate = delegate;
        this.server = server;
        this.profile = profile;
        this.persona = persona;
        this.logTokens = logTokens;
        this.globalMaxResultTokens = globalMaxResultTokens;
        this.tokenCountEstimator = tokenCountEstimator;
    }

    public McpToolProvider provider() {
        // One provider per connection binds the server policy to executor callbacks.
        return McpToolProvider.builder()
                .mcpClients(delegate)
                .filter((client, tool) -> profile.allowsTool(server, tool.name()))
                .toolSpecificationMapper((client, tool) -> exposeTool(tool))
                .toolWrapper(this::wrap)
                .build();
    }

    private ToolExecutor wrap(ToolExecutor executor) {
        return new ToolExecutor() {
            @Override
            public String execute(ToolExecutionRequest request, Object memoryId) {
                return executeWithContext(request, InvocationContext.builder().chatMemoryId(memoryId).build()).resultText();
            }

            @Override
            public ToolExecutionResult executeWithContext(ToolExecutionRequest request, InvocationContext context) {
                ToolExecutionRequest profiled = prepareRequest(request);
                logDim("TOOL " + server + "." + profiled.name() + " REQUEST " + abbreviate(profiled.arguments()));
                try {
                    ToolExecutionResult result = limitResult(executor.executeWithContext(profiled, context));
                    logDim("TOOL " + server + "." + profiled.name() + " RESPONSE isError=" + result.isError()
                            + " " + abbreviate(McpToolResultLimiter.resultText(result)));
                    return result;
                } catch (RuntimeException failure) {
                    logDim("TOOL " + server + "." + profiled.name() + " ERROR " + abbreviate(failure.getMessage()));
                    throw failure;
                }
            }
        };
    }

    private ToolSpecification exposeTool(ToolSpecification tool) {
        ToolSpecification projected = projectArguments(tool);
        if (!qualifiesToolNames()) {
            return projected;
        }
        return projected.toBuilder().name(exposedToolName(tool.name())).build();
    }

    private ToolSpecification projectArguments(ToolSpecification tool) {
        ToolProfile.Arguments policy = profile.arguments(server, tool.name());
        Set<String> hidden = policy.hidden();
        if (policy.permittedValues().isEmpty() && (hidden.isEmpty() || tool.parameters() == null)) {
            return tool;
        }

        JsonObjectSchema original = tool.parameters();
        var properties = new LinkedHashMap<String, JsonSchemaElement>();
        var required = new LinkedHashSet<String>();
        JsonObjectSchema.Builder parameters = JsonObjectSchema.builder();
        if (original != null) {
            properties.putAll(original.properties());
            if (original.required() != null) {
                required.addAll(original.required());
            }
            parameters.description(original.description())
                    .additionalProperties(original.additionalProperties())
                    .definitions(original.definitions());
        }
        hidden.forEach(properties::remove);
        required.removeAll(hidden);
        Set<String> defaults = policy.defaults().keySet();
        policy.permittedValues().forEach((name, values) -> {
            if (!hidden.contains(name)) {
                JsonSchemaElement originalProperty = properties.get(name);
                properties.put(name, JsonEnumSchema.builder()
                        .description(originalProperty == null ? null : originalProperty.description())
                        .enumValues(values)
                        .build());
                if (defaults.contains(name)) {
                    required.remove(name);
                } else {
                    required.add(name);
                }
            }
        });
        return tool.toBuilder().parameters(parameters.addProperties(properties).required(List.copyOf(required)).build()).build();
    }

    public ToolExecutionRequest prepareRequest(ToolExecutionRequest request) {
        String toolName = originalToolName(request.name());
        if (!profile.allowsTool(server, toolName)) {
            throw new IllegalArgumentException("Tool " + request.name() + " is not allowed by profile " + profile.id());
        }
        ToolProfile.Arguments policy = profile.arguments(server, toolName);
        Set<String> hidden = policy.hidden();
        Map<String, JsonNode> defaults = policy.defaults();
        ToolExecutionRequest renamed = toolName.equals(request.name()) ? request : request.toBuilder().name(toolName).build();
        if (hidden.isEmpty() && defaults.isEmpty() && policy.permittedValues().isEmpty()) {
            return renamed;
        }
        ObjectNode arguments = parseArgumentsObject(request.arguments());
        hidden.stream().filter(arguments::has).findFirst().ifPresent(argument -> {
            throw new IllegalArgumentException(
                    "Argument " + argument + " is hidden by profile " + profile.id() + " for tool " + request.name());
        });
        defaults.forEach((name, value) -> {
            if (!arguments.has(name)) {
                arguments.set(name, value);
            }
        });
        policy.permittedValues().forEach((name, values) -> {
            JsonNode effective = arguments.get(name);
            if (effective == null || !effective.isTextual() || !values.contains(effective.textValue())) {
                throw new IllegalArgumentException(
                        "Argument " + name + " must be one of " + values + " for tool " + request.name());
            }
        });
        return renamed.toBuilder().arguments(arguments.toString()).build();
    }

    private boolean qualifiesToolNames() {
        return profile.policies().values().stream().filter(policy -> policy.connection() != null).limit(2).count() == 2;
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
