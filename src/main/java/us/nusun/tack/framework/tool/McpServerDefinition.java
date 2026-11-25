package us.nusun.tack.framework.tool;

import static us.nusun.tack.framework.FrameworkText.firstNonBlank;

import java.util.LinkedHashMap;
import java.util.Map;

public record McpServerDefinition(
        String id,
        String url,
        String transportType,
        String authHeaderName,
        String authHeaderValueFormat,
        Map<String, String> extraHeaders
) {
    public McpServerDefinition {
        transportType = firstNonBlank(transportType, "streamableHttp");
        authHeaderName = firstNonBlank(authHeaderName, "Authorization");
        authHeaderValueFormat = firstNonBlank(authHeaderValueFormat, "Bearer %s");
        extraHeaders = extraHeaders == null ? Map.of() : Map.copyOf(extraHeaders);
    }

    public static Builder builder(String id) { return new Builder(id); }

    public static final class Builder {
        private final String id;
        private String url = "";
        private String transportType;
        private String authHeaderName;
        private String authHeaderValueFormat;
        private final LinkedHashMap<String, String> extraHeaders = new LinkedHashMap<>();

        private Builder(String id) { this.id = id; }
        public Builder url(String url) { this.url = url; return this; }
        public Builder transportType(String transportType) { this.transportType = transportType; return this; }
        public Builder authHeaderName(String authHeaderName) { this.authHeaderName = authHeaderName; return this; }
        public Builder authHeaderValueFormat(String authHeaderValueFormat) { this.authHeaderValueFormat = authHeaderValueFormat; return this; }
        public Builder extraHeader(String name, String value) {
            if (name != null && !name.isBlank() && value != null && !value.isBlank()) {
                this.extraHeaders.put(name, value);
            }
            return this;
        }
        public McpServerDefinition build() {
            return new McpServerDefinition(id, url, transportType, authHeaderName, authHeaderValueFormat, extraHeaders);
        }
    }
}
