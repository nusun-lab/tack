package us.nusun.tack.framework.tool.mcp;

import static us.nusun.tack.framework.FrameworkText.isBlank;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
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

    @ConfigProperty(name = "agent.mcp.logging.transport-requests", defaultValue = "false")
    boolean logTransportRequests;

    @ConfigProperty(name = "agent.mcp.logging.transport-responses", defaultValue = "false")
    boolean logTransportResponses;

    @Inject LogTokens logTokens;

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
            return McpToolPolicy.provider(client, server.id(), profile, persona, logTokens).provideTools(request);
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
        return connections.computeIfAbsent(new ClientCacheKey(server, persona.id(), handle, token), this::newConnection);
    }

    private CachedMcpConnection newConnection(ClientCacheKey cacheKey) {
        McpServerDefinition server = cacheKey.server();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(server.authHeaderName(), server.authHeaderValueFormat().formatted(cacheKey.token()));
        headers.putAll(server.extraHeaders());
        String key = server.id() + ":" + cacheKey.personaId();
        return new CachedMcpConnection(() -> connect(key, server.url(), server.transportType(), headers));
    }

    McpClient connect(String key, String url, String transportType, Map<String, String> headers) {
        warnIfUnsupportedTransport(transportType);
        var transport = StreamableHttpMcpTransport.builder()
                .url(url)
                .customHeaders(ignored -> headers)
                .timeout(Duration.ofMillis(transportTimeoutMs))
                .logRequests(logTransportRequests)
                .logResponses(logTransportResponses)
                .build();
        return DefaultMcpClient.builder()
                .key(key)
                .clientName("tack")
                .clientVersion("0.1.0-SNAPSHOT")
                .transport(transport)
                .initializationTimeout(Duration.ofMillis(initializationTimeoutMs))
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
                        key.server().id(), key.personaId(), rootMessage(failure));
            }
        });
        connections.clear();
    }

    private static void warnIfUnsupportedTransport(String transportType) {
        String normalized = transportType.toLowerCase().replace("-", "").replace("_", "");
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
            McpServerDefinition server,
            String personaId,
            String identityHandle,
            String token) {
        @Override
        public String toString() {
            return "ClientCacheKey[serverId=%s, personaId=%s, identityHandle=%s, credentials=<redacted>]"
                    .formatted(server.id(), personaId, identityHandle);
        }
    }
}
