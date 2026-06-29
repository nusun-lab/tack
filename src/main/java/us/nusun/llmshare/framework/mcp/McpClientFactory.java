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
import org.eclipse.microprofile.config.ConfigProvider;
import us.nusun.llmshare.framework.logging.LogTokens;
import us.nusun.llmshare.framework.mcp.AgentIdentityConfig;
import us.nusun.llmshare.framework.model.ExternalIdentity;
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
        add(clients, persona, profile, "mattermost", () -> mattermost(persona));
        add(clients, persona, profile, "gitea", () -> gitea(persona));
        add(clients, persona, profile, "plane", () -> plane(persona));
        return List.copyOf(clients);
    }

    private void add(List<McpClient> clients, Persona persona, ToolProfile profile, String server, Supplier supplier) {
        if (!profile.allowsServer(server)) {
            return;
        }
        McpClient client = supplier.get();
        if (client != null) {
            clients.add(new ProfiledMcpClient(client, server, profile, persona, logTokens));
        }
    }

    private McpClient mattermost(Persona persona) {
        return build(persona, "mattermost", config.mcp().mattermost().url().orElse(null), config.mcp().mattermost().transportType(), config.mcp().mattermost().authHeaderName().orElse("Authorization"), config.mcp().mattermost().authHeaderValueFormat().orElse("Bearer %s"), Map.of());
    }

    private McpClient gitea(Persona persona) {
        return build(persona, "gitea", config.mcp().gitea().url().orElse(null), config.mcp().gitea().transportType(), config.mcp().gitea().authHeaderName().orElse("Authorization"), config.mcp().gitea().authHeaderValueFormat().orElse("token %s"), Map.of());
    }

    private McpClient plane(Persona persona) {
        LinkedHashMap<String, String> extra = new LinkedHashMap<>();
        config.mcp().plane().workspaceSlug().filter(v -> !v.isBlank()).ifPresent(v -> extra.put("x-workspace-slug", v));
        return build(persona, "plane", config.mcp().plane().url().orElse(null), config.mcp().plane().transportType(), config.mcp().plane().authHeaderName().orElse("Authorization"), config.mcp().plane().authHeaderValueFormat().orElse("Bearer %s"), extra);
    }

    private McpClient build(Persona persona, String server, String url, String transportType, String headerName, String headerFormat, Map<String, String> extraHeaders) {
        ExternalIdentity identity = persona.identityFor(server);
        String handle = identity == null || blank(identity.handle()) ? persona.id() : identity.handle();
        String token = token(identity);
        if (blank(url) || blank(token)) {
            LOG.debug("Skipping MCP client server={} persona={} handle={} tokenEnv={} urlPresent={} tokenPresent={}",
                    server,
                    persona.id(),
                    handle,
                    identity == null ? null : identity.tokenEnvVariable(),
                    !blank(url),
                    !blank(token));
            return null;
        }
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put(headerName, headerFormat.formatted(token));
        headers.putAll(extraHeaders);
        String key = server + ":" + persona.id();
        return new LazyInitializingMcpClient(
                key,
                server,
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

    private String token(ExternalIdentity identity) {
        if (identity == null) {
            return null;
        }
        if (!blank(identity.tokenConfigKey())) {
            String value = ConfigProvider.getConfig().getOptionalValue(identity.tokenConfigKey(), String.class).orElse(null);
            if (!blank(value)) {
                return value;
            }
        }
        if (!blank(identity.tokenEnvVariable())) {
            return System.getenv(identity.tokenEnvVariable());
        }
        return null;
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

    private interface Supplier { McpClient get(); }
}
