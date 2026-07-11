package us.nusun.llmshare.framework.mcp;

import static us.nusun.llmshare.framework.model.FrameworkText.firstNonBlank;
import static us.nusun.llmshare.framework.model.FrameworkText.isBlank;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpHeadersSupplier;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.model.TokenCountEstimator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.nusun.llmshare.framework.logging.LogTokens;
import us.nusun.llmshare.framework.model.ExternalIdentity;
import us.nusun.llmshare.framework.model.McpServerDefinition;
import us.nusun.llmshare.framework.model.Persona;
import us.nusun.llmshare.framework.model.ToolProfile;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private McpClient profiledClient(Persona persona, ToolProfile profile, McpServerDefinition server) {
        McpClient client = build(persona, profile, server);
        return client == null
                ? null
                : new ProfiledMcpClient(client, server.id(), profile, persona, logTokens, maxToolResultTokens, tokenCountEstimator);
    }

    private McpClient build(Persona persona, ToolProfile profile, McpServerDefinition server) {
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
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put(firstNonBlank(server.authHeaderName(), "Authorization"),
                firstNonBlank(server.authHeaderValueFormat(), "Bearer %s").formatted(token));
        headers.putAll(server.extraHeaders() == null ? Map.of() : server.extraHeaders());
        String key = server.id() + ":" + persona.id();
        return new LazyInitializingMcpClient(
                key,
                server.id(),
                handle,
                transportType,
                url,
                transportTimeoutMs,
                initializationTimeoutMs,
                () -> DefaultMcpClient.builder()
                        .key(key)
                        .clientName("llm-share-camel-kafka")
                        .clientVersion("0.1.0-SNAPSHOT")
                        .transport(transport(url, transportType, headers))
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

    private static void warnIfUnsupportedTransport(String transportType) {
        String normalized = transportType == null ? "streamablehttp" : transportType.toLowerCase().replace("-", "").replace("_", "");
        if (!"streamablehttp".equals(normalized)) {
            LOG.warn("Ignoring unsupported MCP transport-type='{}'. Only streamableHttp is supported.", transportType);
        }
    }

}
