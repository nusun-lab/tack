package us.nusun.tack.framework.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public record ToolProfile(
        String id,
        Set<String> servers,
        Map<String, Set<String>> allowedTools,
        Map<String, Set<String>> deniedTools,
        Set<String> allowAllToolServers,
        Map<String, McpServerDefinition> mcpServers,
        Map<String, Map<String, Map<String, JsonNode>>> defaultArguments,
        Map<String, Map<String, Set<String>>> hiddenArguments,
        String promptGuidance,
        int maxResultTokens
) {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public ToolProfile {
        servers = servers == null ? Set.of() : Set.copyOf(servers);
        allowedTools = allowedTools == null ? Map.of() : Map.copyOf(allowedTools);
        deniedTools = deniedTools == null ? Map.of() : Map.copyOf(deniedTools);
        allowAllToolServers = allowAllToolServers == null ? Set.of() : Set.copyOf(allowAllToolServers);
        mcpServers = mcpServers == null ? Map.of() : Map.copyOf(mcpServers);
        defaultArguments = copyDefaultArguments(defaultArguments);
        hiddenArguments = copyHiddenArguments(hiddenArguments);
        promptGuidance = promptGuidance == null ? "" : promptGuidance.strip();
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public boolean none() {
        return servers.isEmpty() || "none".equals(id);
    }

    public boolean allowsServer(String server) {
        return servers.contains(server);
    }

    public boolean allowsTool(String server, String toolName) {
        if (!allowsServer(server)) {
            return false;
        }
        if (deniedTools.getOrDefault(server, Set.of()).contains(toolName)) {
            return false;
        }
        if (allowAllToolServers.contains(server)) {
            return true;
        }
        Set<String> allowed = allowedTools.getOrDefault(server, Set.of());
        return allowed.isEmpty() || allowed.contains(toolName);
    }

    public Map<String, JsonNode> defaultArguments(String server, String toolName) {
        return defaultArguments.getOrDefault(server, Map.of()).getOrDefault(toolName, Map.of());
    }

    public Set<String> hiddenArguments(String server, String toolName) {
        return hiddenArguments.getOrDefault(server, Map.of()).getOrDefault(toolName, Set.of());
    }

    public static final class Builder {
        private final String id;
        private final Set<String> servers = new LinkedHashSet<>();
        private final Map<String, Set<String>> allowedTools = new LinkedHashMap<>();
        private final Map<String, Set<String>> deniedTools = new LinkedHashMap<>();
        private final Set<String> allowAllToolServers = new LinkedHashSet<>();
        private final Map<String, McpServerDefinition> mcpServers = new LinkedHashMap<>();
        private final Map<String, Map<String, Map<String, JsonNode>>> defaultArguments = new LinkedHashMap<>();
        private final Map<String, Map<String, Set<String>>> hiddenArguments = new LinkedHashMap<>();
        private String promptGuidance = "";
        private int maxResultTokens;

        private Builder(String id) {
            this.id = id;
        }

        /**
         * Declares a logical server id for tool allow/deny/default-argument rules only.
         * Use {@link #mcpServer(McpServerDefinition)} when the profile should build
         * an MCP client for that server.
         */
        public Builder server(String server) { this.servers.add(server); return this; }
        /**
         * Declares logical server ids for tool allow/deny/default-argument rules only.
         * Use {@link #mcpServer(McpServerDefinition)} for buildable MCP servers.
         */
        public Builder servers(String... servers) { for (String server : servers) this.servers.add(server); return this; }
        public Builder mcpServer(McpServerDefinition server) {
            if (server != null && server.id() != null && !server.id().isBlank()) {
                this.servers.add(server.id());
                this.mcpServers.put(server.id(), server);
            }
            return this;
        }
        public Builder allowTool(String server, String toolName) {
            this.servers.add(server);
            this.allowedTools.computeIfAbsent(server, ignored -> new LinkedHashSet<>()).add(toolName);
            return this;
        }
        public Builder allowTools(String server, String... toolNames) {
            for (String toolName : toolNames) {
                allowTool(server, toolName);
            }
            return this;
        }
        public Builder denyTool(String server, String toolName) {
            this.servers.add(server);
            this.deniedTools.computeIfAbsent(server, ignored -> new LinkedHashSet<>()).add(toolName);
            return this;
        }
        public Builder denyTools(String server, String... toolNames) {
            for (String toolName : toolNames) {
                denyTool(server, toolName);
            }
            return this;
        }
        public Builder allowAllTools(String server) {
            this.servers.add(server);
            this.allowAllToolServers.add(server);
            return this;
        }
        public Builder defaultArgument(String server, String toolName, String argumentName, String value) { return defaultArgument(server, toolName, argumentName, OBJECT_MAPPER.getNodeFactory().textNode(value)); }
        public Builder defaultArgument(String server, String toolName, String argumentName, boolean value) { return defaultArgument(server, toolName, argumentName, OBJECT_MAPPER.getNodeFactory().booleanNode(value)); }
        public Builder defaultArgument(String server, String toolName, String argumentName, int value) { return defaultArgument(server, toolName, argumentName, OBJECT_MAPPER.getNodeFactory().numberNode(value)); }
        public Builder defaultArgument(String server, String toolName, String argumentName, double value) { return defaultArgument(server, toolName, argumentName, OBJECT_MAPPER.getNodeFactory().numberNode(value)); }
        public Builder defaultArgumentJson(String server, String toolName, String argumentName, String json) {
            try {
                return defaultArgument(server, toolName, argumentName, OBJECT_MAPPER.readTree(json));
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid JSON default argument for " + server + "." + toolName + "." + argumentName, e);
            }
        }
        public Builder defaultArgument(String server, String toolName, String argumentName, JsonNode value) {
            this.defaultArguments
                    .computeIfAbsent(server, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(toolName, ignored -> new LinkedHashMap<>())
                    .put(argumentName, value == null ? OBJECT_MAPPER.nullNode() : value.deepCopy());
            return this;
        }
        public Builder hideArgument(String server, String toolName, String argumentName) {
            this.servers.add(server);
            this.hiddenArguments
                    .computeIfAbsent(server, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(toolName, ignored -> new LinkedHashSet<>())
                    .add(argumentName);
            return this;
        }
        public Builder maxResultTokens(int maxResultTokens) { this.maxResultTokens = maxResultTokens; return this; }
        public Builder promptGuidance(String promptGuidance) { this.promptGuidance = promptGuidance; return this; }

        public ToolProfile build() {
            return new ToolProfile(id, servers, allowedTools, deniedTools, allowAllToolServers, mcpServers, defaultArguments, hiddenArguments, promptGuidance, maxResultTokens);
        }
    }

    private static Map<String, Map<String, Set<String>>> copyHiddenArguments(Map<String, Map<String, Set<String>>> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Map<String, Set<String>>> copy = new LinkedHashMap<>();
        value.forEach((server, tools) -> {
            LinkedHashMap<String, Set<String>> toolCopy = new LinkedHashMap<>();
            tools.forEach((tool, arguments) -> toolCopy.put(tool, Set.copyOf(arguments)));
            copy.put(server, Map.copyOf(toolCopy));
        });
        return Map.copyOf(copy);
    }

    private static Map<String, Map<String, Map<String, JsonNode>>> copyDefaultArguments(Map<String, Map<String, Map<String, JsonNode>>> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Map<String, Map<String, JsonNode>>> copy = new LinkedHashMap<>();
        value.forEach((server, tools) -> {
            LinkedHashMap<String, Map<String, JsonNode>> toolCopy = new LinkedHashMap<>();
            tools.forEach((tool, arguments) -> {
                LinkedHashMap<String, JsonNode> argumentCopy = new LinkedHashMap<>();
                arguments.forEach((name, node) -> argumentCopy.put(name, node == null ? OBJECT_MAPPER.nullNode() : node.deepCopy()));
                toolCopy.put(tool, Map.copyOf(argumentCopy));
            });
            copy.put(server, Map.copyOf(toolCopy));
        });
        return Map.copyOf(copy);
    }
}
