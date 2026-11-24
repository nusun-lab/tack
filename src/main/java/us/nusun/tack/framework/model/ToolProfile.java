package us.nusun.tack.framework.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record ToolProfile(
        String id,
        Map<String, ServerPolicy> policies,
        String promptGuidance,
        int maxResultTokens
) {
    public ToolProfile {
        policies = Map.copyOf(policies);
        promptGuidance = promptGuidance == null ? "" : promptGuidance.strip();
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public Set<String> servers() {
        return policies.entrySet().stream().filter(entry -> entry.getValue().declared())
                .map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
    }

    public Map<String, McpServerDefinition> mcpServers() {
        return policies.entrySet().stream()
                .filter(entry -> entry.getValue().connection() != null)
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().connection()));
    }

    public boolean none() {
        return "none".equals(id) || policies.values().stream().noneMatch(ServerPolicy::declared);
    }

    public boolean allowsServer(String server) {
        ServerPolicy policy = policies.get(server);
        return policy != null && policy.declared();
    }

    public boolean allowsTool(String server, String toolName) {
        ServerPolicy policy = policies.get(server);
        return policy != null && policy.declared() && !policy.denied().contains(toolName)
                && (policy.allowAll() || policy.allowed().isEmpty() || policy.allowed().contains(toolName));
    }

    public Map<String, JsonNode> defaultArguments(String server, String toolName) {
        return arguments(server, toolName).defaults();
    }

    public Set<String> hiddenArguments(String server, String toolName) {
        return arguments(server, toolName).hidden();
    }

    public Arguments arguments(String server, String toolName) {
        ServerPolicy policy = policies.get(server);
        return policy == null ? Arguments.NONE : policy.arguments().getOrDefault(toolName, Arguments.NONE);
    }

    public record ServerPolicy(McpServerDefinition connection, Set<String> allowed, Set<String> denied,
                               boolean allowAll, boolean declared, Map<String, Arguments> arguments) {
        public ServerPolicy {
            allowed = Set.copyOf(allowed);
            denied = Set.copyOf(denied);
            arguments = Map.copyOf(arguments);
        }
    }

    public record Arguments(Map<String, JsonNode> defaults, Set<String> hidden, Map<String, List<String>> permittedValues) {
        private static final Arguments NONE = new Arguments(Map.of(), Set.of(), Map.of());

        public Arguments {
            defaults = copyDefaults(defaults);
            hidden = Set.copyOf(hidden);
            permittedValues = permittedValues.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        }

        @Override
        public Map<String, JsonNode> defaults() {
            return copyDefaults(defaults);
        }

        private static Map<String, JsonNode> copyDefaults(Map<String, JsonNode> defaults) {
            return defaults.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, entry -> entry.getValue().deepCopy()));
        }
    }

    public static final class Builder {
        private static final ObjectMapper ARGUMENT_PARSER = new ObjectMapper();
        private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

        private final String id;
        private final Map<String, ServerBuilder> policies = new LinkedHashMap<>();
        private String promptGuidance = "";
        private int maxResultTokens;

        private Builder(String id) {
            this.id = id;
        }

        private ServerBuilder policy(String server) {
            return policies.computeIfAbsent(server, ignored -> new ServerBuilder());
        }

        /** Declares a logical server for rules. Use mcpServer to register a connection. */
        public Builder server(String server) {
            policy(server).declared = true;
            return this;
        }

        public Builder servers(String... servers) {
            for (String server : servers) {
                server(server);
            }
            return this;
        }

        public Builder mcpServer(McpServerDefinition server) {
            if (server != null && server.id() != null && !server.id().isBlank()) {
                policy(server.id()).connection = server;
                server(server.id());
            }
            return this;
        }

        public Builder allowTool(String server, String toolName) {
            policy(server).allowed.add(toolName);
            server(server);
            return this;
        }

        public Builder allowTools(String server, String... toolNames) {
            for (String toolName : toolNames) {
                allowTool(server, toolName);
            }
            return this;
        }

        public Builder denyTool(String server, String toolName) {
            policy(server).denied.add(toolName);
            server(server);
            return this;
        }

        public Builder denyTools(String server, String... toolNames) {
            for (String toolName : toolNames) {
                denyTool(server, toolName);
            }
            return this;
        }

        public Builder allowAllTools(String server) {
            policy(server).allowAll = true;
            server(server);
            return this;
        }

        public Builder defaultArgument(String server, String tool, String name, String value) {
            return defaultArgument(server, tool, name, NODES.textNode(value));
        }

        public Builder defaultArgument(String server, String tool, String name, boolean value) {
            return defaultArgument(server, tool, name, NODES.booleanNode(value));
        }

        public Builder defaultArgument(String server, String tool, String name, int value) {
            return defaultArgument(server, tool, name, NODES.numberNode(value));
        }

        public Builder defaultArgument(String server, String tool, String name, double value) {
            return defaultArgument(server, tool, name, NODES.numberNode(value));
        }

        public Builder defaultArgumentJson(String server, String tool, String name, String json) {
            try {
                return defaultArgument(server, tool, name, ARGUMENT_PARSER.readTree(json));
            } catch (Exception failure) {
                throw new IllegalArgumentException("Invalid JSON default argument for " + server + "." + tool + "." + name, failure);
            }
        }

        public Builder defaultArgument(String server, String tool, String name, JsonNode value) {
            policy(server).arguments(tool).defaults.put(name, value == null ? NODES.nullNode() : value.deepCopy());
            return this;
        }

        public Builder hideArgument(String server, String tool, String name) {
            policy(server).arguments(tool).hidden.add(name);
            server(server);
            return this;
        }

        public Builder permitArgumentValues(String server, String tool, String name, String... values) {
            List<String> permitted = List.of(values);
            if (permitted.isEmpty()) {
                throw new IllegalArgumentException("Expected at least one permitted value for " + name);
            }
            policy(server).arguments(tool).permittedValues.put(name, permitted);
            return this;
        }

        public Builder maxResultTokens(int maxResultTokens) {
            this.maxResultTokens = maxResultTokens;
            return this;
        }

        public Builder promptGuidance(String promptGuidance) {
            this.promptGuidance = promptGuidance;
            return this;
        }

        public ToolProfile build() {
            Map<String, ServerPolicy> built = new LinkedHashMap<>();
            policies.forEach((name, policy) -> built.put(name, policy.build()));
            return new ToolProfile(id, built, promptGuidance, maxResultTokens);
        }

        private static final class ServerBuilder {
            private McpServerDefinition connection;
            private final Set<String> allowed = new LinkedHashSet<>();
            private final Set<String> denied = new LinkedHashSet<>();
            private boolean allowAll;
            private boolean declared;
            private final Map<String, ArgumentBuilder> arguments = new LinkedHashMap<>();

            private ArgumentBuilder arguments(String tool) {
                return arguments.computeIfAbsent(tool, ignored -> new ArgumentBuilder());
            }

            private ServerPolicy build() {
                Map<String, Arguments> built = new LinkedHashMap<>();
                arguments.forEach((tool, settings) -> built.put(tool, new Arguments(settings.defaults, settings.hidden, settings.permittedValues)));
                return new ServerPolicy(connection, allowed, denied, allowAll, declared, built);
            }
        }

        private static final class ArgumentBuilder {
            private final Map<String, JsonNode> defaults = new LinkedHashMap<>();
            private final Set<String> hidden = new LinkedHashSet<>();
            private final Map<String, List<String>> permittedValues = new LinkedHashMap<>();
        }
    }
}
