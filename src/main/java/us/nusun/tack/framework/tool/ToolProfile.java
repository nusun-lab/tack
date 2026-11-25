package us.nusun.tack.framework.tool;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

public record ToolProfile(
        String id,
        Map<String, ServerPolicy> policies,
        String promptGuidance
) {
    public ToolProfile {
        policies = Map.copyOf(policies);
        promptGuidance = promptGuidance == null ? "" : promptGuidance.strip();
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public boolean none() {
        return "none".equals(id) || policies.isEmpty();
    }

    public boolean allowsTool(String server, String toolName) {
        ServerPolicy policy = policies.get(server);
        return policy != null && !policy.denied().contains(toolName)
                && (policy.allowAll() || policy.allowed().isEmpty() || policy.allowed().contains(toolName));
    }

    public void checkExecution(String server, String tool, String arguments) {
        if (!allowsTool(server, tool)) {
            throw new IllegalArgumentException("Tool " + tool + " is not allowed by profile " + id);
        }
        var authorizer = policies.get(server).authorizer();
        if (authorizer != null) {
            authorizer.accept(tool, arguments);
        }
    }

    public record ServerPolicy(McpServerDefinition connection, Set<String> allowed, Set<String> denied,
                               boolean allowAll, BiConsumer<String, String> authorizer) {
        public ServerPolicy {
            allowed = Set.copyOf(allowed);
            denied = Set.copyOf(denied);
        }
    }

    public static final class Builder {
        private final String id;
        private final Map<String, ServerBuilder> policies = new LinkedHashMap<>();
        private String promptGuidance = "";

        private Builder(String id) {
            this.id = id;
        }

        private ServerBuilder policy(String server) {
            return policies.computeIfAbsent(server, ignored -> new ServerBuilder());
        }

        /** Declares a logical server for rules. Use mcpServer to register a connection. */
        public Builder server(String server) {
            policy(server);
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
            }
            return this;
        }

        public Builder allowTool(String server, String toolName) {
            policy(server).allowed.add(toolName);
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
            return this;
        }

        /** Adds an MCP dispatch check receiving the server's tool name and original arguments. */
        public Builder authorizeMcpExecution(String server, BiConsumer<String, String> authorizer) {
            ServerBuilder policy = policies.get(server);
            if (policy == null || policy.connection == null) {
                throw new IllegalArgumentException("No MCP connection declared for " + server);
            }
            policy.authorizer = authorizer;
            return this;
        }

        public Builder promptGuidance(String promptGuidance) {
            this.promptGuidance = promptGuidance;
            return this;
        }

        public ToolProfile build() {
            Map<String, ServerPolicy> built = new LinkedHashMap<>();
            policies.forEach((name, policy) -> built.put(name, policy.build()));
            return new ToolProfile(id, built, promptGuidance);
        }

        private static final class ServerBuilder {
            private McpServerDefinition connection;
            private final Set<String> allowed = new LinkedHashSet<>();
            private final Set<String> denied = new LinkedHashSet<>();
            private boolean allowAll;
            private BiConsumer<String, String> authorizer;

            private ServerPolicy build() {
                return new ServerPolicy(connection, allowed, denied, allowAll, authorizer);
            }
        }
    }
}
