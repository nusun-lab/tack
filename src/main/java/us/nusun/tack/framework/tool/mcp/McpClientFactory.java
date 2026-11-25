package us.nusun.tack.framework.tool.mcp;

import static us.nusun.tack.framework.FrameworkText.firstNonBlank;
import static us.nusun.tack.framework.FrameworkText.isBlank;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpHeadersSupplier;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.service.IllegalConfigurationException;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.nusun.tack.framework.logging.LogTokens;
import us.nusun.tack.framework.persona.ExternalIdentity;
import us.nusun.tack.framework.tool.McpServerDefinition;
import us.nusun.tack.framework.persona.Persona;
import us.nusun.tack.framework.tool.ToolProfile;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

@ApplicationScoped
public class McpClientFactory {
    private static final Logger LOG = LoggerFactory.getLogger(McpClientFactory.class);

    @ConfigProperty(name = "agent.mcp.transport-timeout-ms", defaultValue = "60000")
    long transportTimeoutMs = 60000;

    @ConfigProperty(name = "agent.mcp.initialization-timeout-ms", defaultValue = "30000")
    long initializationTimeoutMs = 30000;

    @ConfigProperty(name = "agent.mcp.max-tool-result-tokens", defaultValue = "1000")
    int maxToolResultTokens = 1000;

    @ConfigProperty(name = "agent.mcp.logging.transport-requests", defaultValue = "false")
    boolean logTransportRequests;

    @ConfigProperty(name = "agent.mcp.logging.transport-responses", defaultValue = "false")
    boolean logTransportResponses;

    @Inject LogTokens logTokens;
    @Inject TokenCountEstimator tokenCountEstimator;

    private final Map<ClientCacheKey, CachedMcpConnection> connections = new LinkedHashMap<>();
    private boolean closed;

    public List<ToolProvider> toolProvidersFor(Persona persona, ToolProfile profile) {
        if (profile == null || profile.none()) {
            return List.of();
        }
        return profile.policies().values().stream()
                .map(ToolProfile.ServerPolicy::connection)
                .filter(Objects::nonNull)
                .map(server -> toolProvider(persona, profile, server))
                .flatMap(Stream::ofNullable)
                .toList();
    }

    private ToolProvider toolProvider(Persona persona, ToolProfile profile, McpServerDefinition server) {
        CachedMcpConnection connection = cachedConnection(persona, profile, server);
        if (connection == null) {
            return null;
        }
        return request -> {
            McpClient client;
            try {
                client = connection.client();
            } catch (IllegalConfigurationException invalid) {
                throw invalid;
            } catch (RuntimeException unavailable) {
                LOG.warn("Unable to initialize MCP connection server={} persona={} profile={} cause={}",
                        server.id(), persona.id(), profile.id(), rootMessage(unavailable));
                return ToolProviderResult.builder().build();
            }
            return new McpToolPolicy(client, server.id(), profile, persona, logTokens,
                    maxToolResultTokens, tokenCountEstimator).provider().provideTools(request);
        };
    }

    private synchronized CachedMcpConnection cachedConnection(Persona persona, ToolProfile profile, McpServerDefinition server) {
        if (closed) {
            throw new IllegalStateException("MCP client factory is closed");
        }
        ExternalIdentity identity = persona.identityFor(server.id());
        String handle = identity == null || isBlank(identity.handle()) ? persona.id() : identity.handle();
        String token = identity == null ? null : identity.token();
        String url = server.url();
        if (isBlank(url) || isBlank(token)) {
            LOG.info("Skipping MCP client profile={} server={} persona={} handle={} urlPresent={} tokenPresent={}",
                    profile.id(),
                    server.id(),
                    persona.id(),
                    handle,
                    !isBlank(url),
                    !isBlank(token));
            return null;
        }
        String transportType = firstNonBlank(server.transportType(), "streamableHttp");
        String authHeaderName = firstNonBlank(server.authHeaderName(), "Authorization");
        String authHeaderValueFormat = firstNonBlank(server.authHeaderValueFormat(), "Bearer %s");
        ClientCacheKey cacheKey = new ClientCacheKey(
                server.id(),
                persona.id(),
                handle,
                transportType,
                url,
                token,
                authHeaderName,
                authHeaderValueFormat,
                server.extraHeaders());
        return connections.computeIfAbsent(cacheKey, this::newConnection);
    }

    private CachedMcpConnection newConnection(ClientCacheKey cacheKey) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(cacheKey.authHeaderName(), cacheKey.authHeaderValueFormat().formatted(cacheKey.token()));
        headers.putAll(cacheKey.extraHeaders());
        String key = cacheKey.serverId() + ":" + cacheKey.personaId();
        return new CachedMcpConnection(() -> connect(key, cacheKey.url(), cacheKey.transportType(), headers));
    }

    McpClient connect(String key, String url, String transportType, Map<String, String> headers) {
        return DefaultMcpClient.builder()
                .key(key)
                .clientName("tack")
                .clientVersion("0.1.0-SNAPSHOT")
                .transport(transport(url, transportType, headers))
                .initializationTimeout(Duration.ofMillis(initializationTimeoutMs))
                .build();
    }

    private McpTransport transport(String url, String transportType, Map<String, String> headers) {
        McpHeadersSupplier headersSupplier = ignored -> headers;
        warnIfUnsupportedTransport(transportType);
        return StreamableHttpMcpTransport.builder()
                .url(url)
                .customHeaders(headersSupplier)
                .timeout(Duration.ofMillis(transportTimeoutMs))
                .logRequests(logTransportRequests)
                .logResponses(logTransportResponses)
                .build();
    }

    @PreDestroy
    synchronized void closeCachedClients() {
        closed = true;
        connections.forEach((key, connection) -> {
            try {
                connection.close();
            } catch (Exception failure) {
                LOG.warn("Failed to close MCP connection server={} persona={} cause={}",
                        key.serverId(), key.personaId(), rootMessage(failure));
            }
        });
        connections.clear();
    }

    private static void warnIfUnsupportedTransport(String transportType) {
        String normalized = transportType == null ? "streamablehttp" : transportType.toLowerCase().replace("-", "").replace("_", "");
        if (!"streamablehttp".equals(normalized)) {
            LOG.warn("Ignoring unsupported MCP transport-type='{}'. Only streamableHttp is supported.", transportType);
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private record ClientCacheKey(
            String serverId,
            String personaId,
            String identityHandle,
            String transportType,
            String url,
            String token,
            String authHeaderName,
            String authHeaderValueFormat,
            Map<String, String> extraHeaders) {
        @Override
        public String toString() {
            return "ClientCacheKey[serverId=%s, personaId=%s, identityHandle=%s, transportType=%s, url=%s, token=<redacted>]"
                    .formatted(serverId, personaId, identityHandle, transportType, url);
        }
    }
}
