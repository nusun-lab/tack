package us.nusun.tack.framework.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record ToolProfileView(
        String id,
        Set<String> servers,
        Map<String, Set<String>> allowedTools,
        Map<String, Set<String>> deniedTools,
        Set<String> allowAllToolServers,
        Map<String, Server> mcpServers,
        Map<String, Map<String, Map<String, JsonNode>>> defaultArguments,
        Map<String, Map<String, Set<String>>> hiddenArguments,
        Map<String, Map<String, Map<String, List<String>>>> permittedArgumentValues,
        String promptGuidance,
        int maxResultTokens
) {
    public static ToolProfileView of(ToolProfile profile) {
        Map<String, Set<String>> allowed = new LinkedHashMap<>();
        Map<String, Set<String>> denied = new LinkedHashMap<>();
        Set<String> allowAll = new LinkedHashSet<>();
        Map<String, Map<String, Map<String, JsonNode>>> defaults = new LinkedHashMap<>();
        Map<String, Map<String, Set<String>>> hidden = new LinkedHashMap<>();
        Map<String, Map<String, Map<String, List<String>>>> permitted = new LinkedHashMap<>();
        profile.policies().forEach((server, policy) -> {
            if (!policy.allowed().isEmpty()) allowed.put(server, policy.allowed());
            if (!policy.denied().isEmpty()) denied.put(server, policy.denied());
            if (policy.allowAll()) allowAll.add(server);
            policy.arguments().forEach((tool, arguments) -> {
                if (!arguments.defaults().isEmpty()) {
                    defaults.computeIfAbsent(server, ignored -> new LinkedHashMap<>()).put(tool, arguments.defaults());
                }
                if (!arguments.hidden().isEmpty()) {
                    hidden.computeIfAbsent(server, ignored -> new LinkedHashMap<>()).put(tool, arguments.hidden());
                }
                if (!arguments.permittedValues().isEmpty()) {
                    permitted.computeIfAbsent(server, ignored -> new LinkedHashMap<>()).put(tool, arguments.permittedValues());
                }
            });
        });
        return new ToolProfileView(profile.id(), profile.servers(), allowed, denied, allowAll,
                profile.mcpServers().entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Server.of(entry.getValue()))),
                defaults, hidden, permitted, profile.promptGuidance(), profile.maxResultTokens());
    }

    public record Server(String id, String url, String transportType, String authHeaderName, List<String> extraHeaderNames) {
        private static Server of(McpServerDefinition definition) {
            return new Server(definition.id(), definition.url(), definition.transportType(), definition.authHeaderName(),
                    definition.extraHeaders() == null ? List.of() : definition.extraHeaders().keySet().stream().sorted().toList());
        }
    }
}
