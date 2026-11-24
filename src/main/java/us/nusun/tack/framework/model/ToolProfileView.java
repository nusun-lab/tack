package us.nusun.tack.framework.model;

import com.fasterxml.jackson.databind.JsonNode;

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
        String promptGuidance,
        int maxResultTokens
) {
    public static ToolProfileView of(ToolProfile profile) {
        return new ToolProfileView(profile.id(), profile.servers(), profile.allowedTools(), profile.deniedTools(),
                profile.allowAllToolServers(),
                profile.mcpServers().entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Server.of(entry.getValue()))),
                profile.defaultArguments(), profile.hiddenArguments(), profile.promptGuidance(), profile.maxResultTokens());
    }

    public record Server(String id, String url, String transportType, String authHeaderName, List<String> extraHeaderNames) {
        private static Server of(McpServerDefinition definition) {
            return new Server(definition.id(), definition.url(), definition.transportType(), definition.authHeaderName(),
                    definition.extraHeaders() == null ? List.of() : definition.extraHeaders().keySet().stream().sorted().toList());
        }
    }
}
