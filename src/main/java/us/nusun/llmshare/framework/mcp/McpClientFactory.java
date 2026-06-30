package us.nusun.llmshare.framework.mcp;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpHeadersSupplier;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.nusun.llmshare.framework.logging.LogTokens;
import us.nusun.llmshare.framework.model.ExternalIdentity;
import us.nusun.llmshare.framework.model.McpServerDefinition;
import us.nusun.llmshare.framework.model.Persona;
import us.nusun.llmshare.framework.model.ToolProfile;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class McpClientFactory {
    private static final Logger LOG = LoggerFactory.getLogger(McpClientFactory.class);

    @Inject AgentIdentityConfig config;
    @Inject LogTokens logTokens;

    public List<McpClient> clientsFor(Persona persona, ToolProfile profile) {
        if (profile == null || profile.none()) {
            return List.of();
        }
        ArrayList<McpClient> clients = new ArrayList<>();
        for (McpServerDefinition server : profile.mcpServers().values()) {
            add(clients, persona, profile, server);
        }
        return List.copyOf(clients);
    }

    private void add(List<McpClient> clients, Persona persona, ToolProfile profile, McpServerDefinition server) {
        if (!profile.allowsServer(server.id())) {
            return;
        }
        McpClient client = build(persona, server);
        if (client != null) {
            clients.add(new ProfiledMcpClient(client, server.id(), profile, persona, logTokens, config.mcp().maxToolResultCharacters()));
        }
    }

    private McpClient build(Persona persona, McpServerDefinition server) {
        ExternalIdentity identity = persona.identityFor(server.id());
        String handle = identity == null || blank(identity.handle()) ? persona.id() : identity.handle();
        String token = identity == null ? null : identity.token();
        String url = server.url();
        if (blank(url) || blank(token)) {
            LOG.debug("Skipping MCP client server={} persona={} handle={} urlPresent={} tokenPresent={}",
                    server.id(),
                    persona.id(),
                    handle,
                    !blank(url),
                    !blank(token));
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
                config.mcp().transportTimeoutMs(),
                config.mcp().initializationTimeoutMs(),
                () -> DefaultMcpClient.builder()
                        .key(key)
                        .clientName("llm-share-camel-kafka")
                        .clientVersion("0.1.0-SNAPSHOT")
                        .transport(transport(url, transportType, headers))
                        .initializationTimeout(Duration.ofMillis(config.mcp().initializationTimeoutMs()))
                .build());
    }

    private McpTransport transport(String url, String transportType, Map<String, String> headers) {
        McpHeadersSupplier headersSupplier = ignored -> headers;
        warnIfUnsupportedTransport(transportType);
        return StreamableHttpMcpTransport.builder()
                .url(url)
                .customHeaders(headersSupplier)
                .timeout(Duration.ofMillis(config.mcp().transportTimeoutMs()))
                .logRequests(config.mcp().logging().transportRequests())
                .logResponses(config.mcp().logging().transportResponses())
                .build();
    }

    private static void warnIfUnsupportedTransport(String transportType) {
        String normalized = transportType == null ? "streamablehttp" : transportType.toLowerCase().replace("-", "").replace("_", "");
        if (!"streamablehttp".equals(normalized)) {
            LOG.warn("Ignoring unsupported MCP transport-type='{}'. Only streamableHttp is supported.", transportType);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String firstNonBlank(String first, String second) {
        return blank(first) ? second : first;
    }
}
