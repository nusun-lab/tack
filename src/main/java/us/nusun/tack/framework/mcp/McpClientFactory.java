package us.nusun.tack.framework.mcp;

import static us.nusun.tack.framework.model.FrameworkText.firstNonBlank;
import static us.nusun.tack.framework.model.FrameworkText.isBlank;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpHeadersSupplier;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.model.TokenCountEstimator;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.nusun.tack.framework.logging.LogTokens;
import us.nusun.tack.framework.model.ExternalIdentity;
import us.nusun.tack.framework.model.McpServerDefinition;
import us.nusun.tack.framework.model.Persona;
import us.nusun.tack.framework.model.ToolProfile;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
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

    private final ConcurrentMap<ClientCacheKey, LazyInitializingMcpClient> clientCache = new ConcurrentHashMap<>();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();

    public List<McpClient> clientsFor(Persona persona, ToolProfile profile) {
        if (profile == null || profile.none()) {
            return List.of();
        }
        List<McpClient> clients = profile.mcpServers().values().stream()
                .filter(server -> profile.allowsServer(server.id()))
                .map(server -> profiledClient(persona, profile, server))
                .flatMap(Stream::ofNullable)
                .toList();
        if (clients.isEmpty() && !profile.mcpServers().isEmpty()) {
            LOG.warn("No MCP clients selected profile={} persona={} configuredServers={}",
                    profile.id(),
                    persona.id(),
                    profile.mcpServers().keySet());
        }
        return clients;
    }

    public int cachedClientCount() {
        return clientCache.size();
    }

    public long cacheHits() {
        return cacheHits.get();
    }

    public long cacheMisses() {
        return cacheMisses.get();
    }

    private McpClient profiledClient(Persona persona, ToolProfile profile, McpServerDefinition server) {
        McpClient client = cachedClient(persona, profile, server);
        return client == null
                ? null
                : new ProfiledMcpClient(client, server.id(), profile, persona, logTokens, maxToolResultTokens, tokenCountEstimator, false);
    }

    private McpClient cachedClient(Persona persona, ToolProfile profile, McpServerDefinition server) {
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
        Map<String, String> extraHeaders = server.extraHeaders() == null ? Map.of() : Map.copyOf(server.extraHeaders());
        ClientCacheKey cacheKey = new ClientCacheKey(
                server.id(),
                persona.id(),
                handle,
                transportType,
                url,
                token,
                authHeaderName,
                authHeaderValueFormat,
                extraHeaders);
        LazyInitializingMcpClient existing = clientCache.get(cacheKey);
        if (existing != null) {
            cacheHits.incrementAndGet();
            return existing;
        }
        LazyInitializingMcpClient created = buildCachedClient(cacheKey);
        LazyInitializingMcpClient raced = clientCache.putIfAbsent(cacheKey, created);
        if (raced != null) {
            cacheHits.incrementAndGet();
            return raced;
        }
        long misses = cacheMisses.incrementAndGet();
        LOG.info("Cached MCP client service={} persona={} identity={} activeClients={} cacheMisses={}",
                server.id(), persona.id(), handle, clientCache.size(), misses);
        return created;
    }

    private LazyInitializingMcpClient buildCachedClient(ClientCacheKey cacheKey) {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put(cacheKey.authHeaderName(), cacheKey.authHeaderValueFormat().formatted(cacheKey.token()));
        headers.putAll(cacheKey.extraHeaders());
        String key = cacheKey.serverId() + ":" + cacheKey.personaId();
        return new LazyInitializingMcpClient(
                key,
                cacheKey.serverId(),
                cacheKey.identityHandle(),
                cacheKey.transportType(),
                cacheKey.url(),
                transportTimeoutMs,
                initializationTimeoutMs,
                () -> DefaultMcpClient.builder()
                        .key(key)
                        .clientName("tack")
                        .clientVersion("0.1.0-SNAPSHOT")
                        .transport(transport(cacheKey.url(), cacheKey.transportType(), headers))
                        .initializationTimeout(Duration.ofMillis(initializationTimeoutMs))
                        .build());
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
    void closeCachedClients() {
        int cached = clientCache.size();
        int closed = 0;
        for (LazyInitializingMcpClient client : clientCache.values()) {
            try {
                client.close();
                closed++;
            } catch (Exception e) {
                LOG.warn("Failed to close cached MCP client key={} cause={}", client.key(), rootMessage(e));
            }
        }
        clientCache.clear();
        LOG.info("Closed MCP client cache cached={} closed={} hits={} misses={}",
                cached, closed, cacheHits.get(), cacheMisses.get());
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
