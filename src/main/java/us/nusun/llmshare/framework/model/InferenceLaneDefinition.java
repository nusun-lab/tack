package us.nusun.llmshare.framework.model;

public record InferenceLaneDefinition(
        String id,
        String requestTopic,
        String responseTopic,
        int concurrency,
        String endpoint,
        String apiKeyConfigKey,
        String apiKeyEnvVariable
) {
    public static Builder builder(String id) { return new Builder(id); }

    public static final class Builder {
        private final String id;
        private String requestTopic;
        private String responseTopic;
        private int concurrency = 1;
        private String endpoint = "";
        private String apiKeyConfigKey = "";
        private String apiKeyEnvVariable = "";

        private Builder(String id) { this.id = id; }
        public Builder requestTopic(String requestTopic) { this.requestTopic = requestTopic; return this; }
        public Builder responseTopic(String responseTopic) { this.responseTopic = responseTopic; return this; }
        public Builder concurrency(int concurrency) { this.concurrency = concurrency; return this; }
        public Builder endpoint(String endpoint) { this.endpoint = endpoint; return this; }
        public Builder apiKeyConfigKey(String apiKeyConfigKey) { this.apiKeyConfigKey = apiKeyConfigKey; return this; }
        public Builder apiKeyEnvVariable(String apiKeyEnvVariable) { this.apiKeyEnvVariable = apiKeyEnvVariable; return this; }
        public InferenceLaneDefinition build() { return new InferenceLaneDefinition(id, requestTopic, responseTopic, concurrency, endpoint, apiKeyConfigKey, apiKeyEnvVariable); }
    }
}
