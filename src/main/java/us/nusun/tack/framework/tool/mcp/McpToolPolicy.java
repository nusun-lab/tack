package us.nusun.tack.framework.tool.mcp;

import static us.nusun.tack.framework.logging.LogTokens.abbreviateToolValue;

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
import us.nusun.tack.framework.persona.Persona;
import us.nusun.tack.framework.tool.ToolProfile;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class McpToolPolicy {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final McpClient delegate;
    private final String server;
    private final ToolProfile profile;
    private final Persona persona;
    private final LogTokens logTokens;
    private final String toolNamePrefix;
    private final int maxResultTokens;
    private final TokenCountEstimator tokenCountEstimator;

    public McpToolPolicy(McpClient delegate, String server, ToolProfile profile, Persona persona,
                         LogTokens logTokens, int globalMaxResultTokens, TokenCountEstimator tokenCountEstimator) {
        this.delegate = delegate;
        this.server = server;
        this.profile = profile;
        this.persona = persona;
        this.logTokens = logTokens;
        boolean multipleServers = profile.policies().values().stream()
                .filter(policy -> policy.connection() != null).limit(2).count() == 2;
        this.toolNamePrefix = multipleServers ? server.replaceAll("[^A-Za-z0-9_]", "_") + "__" : "";
        this.maxResultTokens = McpToolResultLimiter.effectiveLimit(profile.maxResultTokens(), globalMaxResultTokens);
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
                logDim("TOOL " + server + "." + profiled.name() + " REQUEST " + abbreviateToolValue(profiled.arguments()));
                try {
                    ToolExecutionResult result = McpToolResultLimiter.limit(
                            executor.executeWithContext(profiled, context), maxResultTokens, tokenCountEstimator);
                    logDim("TOOL " + server + "." + profiled.name() + " RESPONSE isError=" + result.isError()
                            + " " + abbreviateToolValue(McpToolResultLimiter.resultText(result)));
                    return result;
                } catch (RuntimeException failure) {
                    logDim("TOOL " + server + "." + profiled.name() + " ERROR " + abbreviateToolValue(failure.getMessage()));
                    throw failure;
                }
            }
        };
    }

    private ToolSpecification exposeTool(ToolSpecification tool) {
        ToolSpecification projected = projectArguments(tool);
        return toolNamePrefix.isEmpty() ? projected : projected.toBuilder().name(toolNamePrefix + tool.name()).build();
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

    private String originalToolName(String toolName) {
        return toolName != null && toolName.startsWith(toolNamePrefix) ? toolName.substring(toolNamePrefix.length()) : toolName;
    }

    private void logDim(String message) {
        if (logTokens != null) {
            logTokens.printTool(persona, message);
        }
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
