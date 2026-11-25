package us.nusun.tack.framework.tool;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record ToolProfileView(
        String id,
        Map<String, ServerPolicy> servers,
        String promptGuidance
) {
    public static ToolProfileView of(ToolProfile profile) {
        Map<String, ServerPolicy> servers = profile.policies().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> ServerPolicy.of(entry.getValue())));
        return new ToolProfileView(profile.id(), servers, profile.promptGuidance());
    }

    public record ServerPolicy(
            Connection connection,
            Set<String> allowedTools,
            Set<String> deniedTools,
            boolean allowAll
    ) {
        private static ServerPolicy of(ToolProfile.ServerPolicy policy) {
            Connection connection = policy.connection() == null ? null : Connection.of(policy.connection());
            return new ServerPolicy(connection, policy.allowed(), policy.denied(), policy.allowAll());
        }
    }

    public record Connection(String url, String transportType, String authHeaderName, List<String> extraHeaderNames) {
        private static Connection of(McpServerDefinition server) {
            return new Connection(server.url(), server.transportType(), server.authHeaderName(),
                    server.extraHeaders().keySet().stream().sorted().toList());
        }
    }
}
