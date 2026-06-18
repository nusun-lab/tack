package us.nusun.llmshare.mcp;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpHeadersSupplier;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.agent.tool.ToolSpecification;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.nusun.llmshare.bean.LogTokens;
import us.nusun.llmshare.bean.RoutineToolTraceTracker;
import us.nusun.llmshare.config.AgentIdentityConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

@ApplicationScoped
public class McpClientFactory {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final Logger LOG = LoggerFactory.getLogger(McpClientFactory.class);

    private final AgentIdentityConfig config;
    private final LogTokens logTokens;
    private final RoutineToolTraceTracker routineToolTraceTracker;

    @Inject
    public McpClientFactory(AgentIdentityConfig config, LogTokens logTokens, RoutineToolTraceTracker routineToolTraceTracker) {
        this.config = config;
        this.logTokens = logTokens;
        this.routineToolTraceTracker = routineToolTraceTracker;
    }

    public McpClient mattermost(String identityKey) {
        return buildClient(identityKey, mattermostConnection(identityKey));
    }

    public McpClient gitea(String identityKey) {
        return buildClient(identityKey, giteaConnection(identityKey));
    }

    public McpClient plane(String identityKey) {
        return buildClient(identityKey, planeConnection(identityKey));
    }

    public List<McpClient> clientsFor(String identityKey, String toolProfile) {
        List<McpClient> clients = new ArrayList<>();
        addIfPresent(clients, mattermost(identityKey));
        addIfPresent(clients, gitea(identityKey));
        addIfPresent(clients, plane(identityKey));
        return List.copyOf(clients);
    }

    public BiPredicate<McpClient, ToolSpecification> toolFilterFor(String toolProfile) {
        return (client, toolSpecification) -> {
            ClientKeyParts keyParts = parseClientKey(client.key());
            boolean allowed = !disabledToolsForService(keyParts.service()).contains(toolSpecification.name());
            LOG.debug("MCP tool filter service={} identity={} tool={} allowed={} profile={}",
                    keyParts.service(),
                    keyParts.identity(),
                    toolSpecification.name(),
                    allowed,
                    toolProfile);
            return allowed;
        };
    }

    public McpServiceConnection mattermostConnection(String identityKey) {
        return serviceConnection(
                identityKey,
                "mattermost",
                config.mcp().mattermost().url().orElse(null),
                config.mcp().mattermost().transportType(),
                config.mcp().mattermost().authHeaderName().orElse(null),
                config.mcp().mattermost().authHeaderValueFormat().orElse(null),
                role(identityKey).mattermost().token().orElse(null),
                Map.of(),
                "Authorization",
                "Bearer %s");
    }

    public McpServiceConnection giteaConnection(String identityKey) {
        return serviceConnection(
                identityKey,
                "gitea",
                config.mcp().gitea().url().orElse(null),
                config.mcp().gitea().transportType(),
                config.mcp().gitea().authHeaderName().orElse(null),
                config.mcp().gitea().authHeaderValueFormat().orElse(null),
                role(identityKey).gitea().token().orElse(null),
                Map.of(),
                "Authorization",
                "token %s");
    }

    public McpServiceConnection planeConnection(String identityKey) {
        Map<String, String> extraHeaders = new LinkedHashMap<>();
        String workspaceSlug = blankToNull(config.mcp().plane().workspaceSlug().orElse(null));
        if (workspaceSlug != null) {
            extraHeaders.put("x-workspace-slug", workspaceSlug);
        }
        return serviceConnection(
                identityKey,
                "plane",
                config.mcp().plane().url().orElse(null),
                config.mcp().plane().transportType(),
                config.mcp().plane().authHeaderName().orElse(null),
                config.mcp().plane().authHeaderValueFormat().orElse(null),
                role(identityKey).plane().token().orElse(null),
                extraHeaders,
                "Authorization",
                "Bearer %s");
    }

    public boolean startupCheckEnabled() {
        return config.mcp().startupCheck().enabled();
    }

    public boolean startupCheckFailFast() {
        return config.mcp().startupCheck().failFast();
    }

    public void logStartupConfiguration() {
        for (String identityKey : List.of("director", "project-manager", "product-owner", "application-developer")) {
            logConnection(identityKey, mattermostConnection(identityKey));
            logConnection(identityKey, giteaConnection(identityKey));
            logConnection(identityKey, planeConnection(identityKey));
        }
    }

    public void checkHealth(String identityKey, String serviceName) {
        McpServiceConnection connection = switch (serviceName) {
            case "mattermost" -> mattermostConnection(identityKey);
            case "gitea" -> giteaConnection(identityKey);
            case "plane" -> planeConnection(identityKey);
            default -> throw new IllegalArgumentException("Unknown MCP service: " + serviceName);
        };
        if (connection == null || !connection.isUsable()) {
            LOG.info("Skipping MCP startup health check service={} identity={} because configuration is incomplete", serviceName, identityKey);
            return;
        }
        LOG.info("Running MCP startup health check service={} identity={} transport={} url={} transportTimeoutMs={} initializationTimeoutMs={}",
                connection.serviceName(), identityKey, connection.transportType(), connection.url(),
                config.mcp().transportTimeoutMs(), config.mcp().initializationTimeoutMs());
        McpClient client = buildClient(identityKey, connection);
        try {
            client.checkHealth();
            LOG.info("MCP startup health check succeeded service={} identity={} url={}",
                    connection.serviceName(), identityKey, connection.url());
        } finally {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
    }

    private McpClient buildClient(String identityKey, McpServiceConnection connection) {
        if (connection == null || !connection.isUsable()) {
            if (connection != null) {
                LOG.info("MCP client not created service={} identity={} usable={} urlPresent={} tokenPresent={} headerCount={}",
                        connection.serviceName(), identityKey, false, connection.url() != null, connection.hasToken(), connection.headers().size());
            }
            return null;
        }

        String key = connection.serviceName() + ":" + connection.identityKey();
        LOG.info("Preparing lazy MCP client service={} identity={} requestedBy={} transport={} url={} transportTimeoutMs={} initializationTimeoutMs={}",
                connection.serviceName(),
                connection.identityKey(),
                identityKey,
                connection.transportType(),
                connection.url(),
                config.mcp().transportTimeoutMs(),
                config.mcp().initializationTimeoutMs());

        McpClient client = new LazyInitializingMcpClient(
                key,
                connection.serviceName(),
                connection.identityKey(),
                connection.transportType(),
                connection.url(),
                config.mcp().transportTimeoutMs(),
                config.mcp().initializationTimeoutMs(),
                () -> {
                    McpTransport transport = transport(connection);
                    return DefaultMcpClient.builder()
                            .key(key)
                            .clientName("llm-share-camel-kafka")
                            .clientVersion("0.1.0-SNAPSHOT")
                            .transport(transport)
                            .initializationTimeout(Duration.ofMillis(config.mcp().initializationTimeoutMs()))
                            .toolResultExtractor(new BoundedMcpToolResultExtractor(config.mcp().maxToolResultCharacters()))
                            .build();
                });
        return config.mcp().logging().enabled()
                ? new LoggingMcpClient(
                        client,
                        connection.serviceName(),
                        connection.identityKey(),
                        agentLabel(connection.identityKey()),
                        logTokens,
                        routineToolTraceTracker)
                : client;
    }

    private McpTransport transport(McpServiceConnection connection) {
        String transportType = normalizeTransportType(connection.transportType());
        McpHeadersSupplier headersSupplier = ignored -> connection.headers();
        return switch (transportType) {
            case "streamablehttp", "streamable-http", "streamable_http" -> StreamableHttpMcpTransport.builder()
                    .url(connection.url())
                    .customHeaders(headersSupplier)
                    .timeout(Duration.ofMillis(config.mcp().transportTimeoutMs()))
                    .logRequests(config.mcp().logging().transportRequests())
                    .logResponses(config.mcp().logging().transportResponses())
                    .build();
            case "http", "sse" -> HttpMcpTransport.builder()
                    .sseUrl(connection.url())
                    .customHeaders(headersSupplier)
                    .timeout(Duration.ofMillis(config.mcp().transportTimeoutMs()))
                    .logRequests(false)
                    .logResponses(config.mcp().logging().transportResponses())
                    .build();
            default -> throw new IllegalArgumentException(
                    "Unsupported MCP transport type '" + connection.transportType() + "' for service "
                            + connection.serviceName());
        };
    }

    private McpServiceConnection serviceConnection(
            String identityKey,
            String serviceName,
            String url,
            String transportType,
            String configuredHeaderName,
            String configuredHeaderValueFormat,
            String token,
            Map<String, String> extraHeaders,
            String defaultHeaderName,
            String defaultHeaderValueFormat) {
        Map<String, String> headers = new LinkedHashMap<>();
        String effectiveHeaderName = firstNonBlank(configuredHeaderName, defaultHeaderName);
        String effectiveHeaderFormat = firstNonBlank(configuredHeaderValueFormat, defaultHeaderValueFormat);
        String effectiveToken = blankToNull(token);
        if (effectiveHeaderName != null && effectiveToken != null) {
            headers.put(effectiveHeaderName, effectiveHeaderFormat.formatted(effectiveToken));
        }
        extraHeaders.forEach((name, value) -> {
            if (blankToNull(name) != null && blankToNull(value) != null) {
                headers.put(name, value);
            }
        });
        return new McpServiceConnection(
                serviceName,
                blankToNull(url),
                firstNonBlank(transportType, "streamableHttp"),
                headers,
                identityKey,
                effectiveToken != null);
    }

    private AgentIdentityConfig.Identity.Role role(String identityKey) {
        return switch (identityKey) {
            case "director" -> config.identity().director();
            case "project-manager" -> config.identity().projectManager();
            case "product-owner" -> config.identity().productOwner();
            case "application-developer" -> config.identity().applicationDeveloper();
            default -> throw new IllegalArgumentException("Unknown MCP identity key: " + identityKey);
        };
    }

    private static String agentLabel(String identityKey) {
        return switch (identityKey) {
            case "director" -> "DIR";
            case "project-manager" -> "PM";
            case "product-owner" -> "PO";
            case "application-developer" -> "DEV";
            case "routine-formatter" -> "FMT";
            default -> identityKey;
        };
    }

    private Set<String> disabledToolsForService(String serviceName) {
        return switch (serviceName) {
            case "mattermost" -> disabledTools(config.mcp().mattermost());
            case "gitea" -> disabledTools(config.mcp().gitea());
            case "plane" -> disabledTools(config.mcp().plane());
            default -> Collections.emptySet();
        };
    }

    private static Set<String> disabledTools(AgentIdentityConfig.Mcp.Service service) {
        return service.disabledTools()
                .map(McpClientFactory::normalizeToolSet)
                .orElseGet(Collections::emptySet);
    }

    private static Set<String> normalizeToolSet(List<String> toolNames) {
        Set<String> values = new HashSet<>();
        for (String toolName : toolNames) {
            String normalized = blankToNull(toolName);
            if (normalized != null) {
                values.add(normalized);
            }
        }
        return Collections.unmodifiableSet(values);
    }

    private static ClientKeyParts parseClientKey(String clientKey) {
        String normalized = blankToNull(clientKey);
        if (normalized == null) {
            throw new IllegalArgumentException("MCP client key must not be blank");
        }
        int separator = normalized.indexOf(':');
        if (separator <= 0 || separator == normalized.length() - 1) {
            throw new IllegalArgumentException("Unsupported MCP client key: " + normalized);
        }
        return new ClientKeyParts(normalized.substring(0, separator), normalized.substring(separator + 1));
    }

    private static void addIfPresent(List<McpClient> clients, McpClient client) {
        if (client != null) {
            clients.add(client);
        }
    }

    private void logConnection(String identityKey, McpServiceConnection connection) {
        LOG.info("MCP config service={} identity={} usable={} transport={} url={} transportTimeoutMs={} initializationTimeoutMs={} authHeaderNames={}",
                connection.serviceName(),
                identityKey,
                connection.isUsable(),
                connection.transportType(),
                connection.url(),
                config.mcp().transportTimeoutMs(),
                config.mcp().initializationTimeoutMs(),
                connection.headers().keySet());
    }

    private static String normalizeTransportType(String transportType) {
        return transportType == null ? "streamablehttp" : transportType.toLowerCase(Locale.ROOT).trim();
    }

    private static String firstNonBlank(String first, String second) {
        String value = blankToNull(first);
        return value != null ? value : blankToNull(second);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record McpServiceConnection(
            String serviceName,
            String url,
            String transportType,
            Map<String, String> headers,
            String identityKey,
            boolean hasToken
    ) {
        public boolean isUsable() {
            return url != null && !headers.isEmpty() && hasToken;
        }
    }

    private record ClientKeyParts(String service, String identity) {
    }
}
