package us.nusun.llmshare.framework.model;

public record InferenceLaneDefinition(
        String id,
        String requestTopic,
        String responseTopic,
        int parallelism,
        PartitionKeyStrategy partitionKeyStrategy,
        String endpoint,
        String model,
        String apiKeyConfigKey,
        String apiKeyEnvVariable
) {
    public InferenceLaneDefinition {
        if (parallelism < 1) {
            throw new IllegalArgumentException("Inference lane parallelism must be at least 1 for lane " + id);
        }
        partitionKeyStrategy = partitionKeyStrategy == null ? PartitionKeyStrategy.AGENT_RUN : partitionKeyStrategy;
    }

    /**
     * @deprecated Use {@link #parallelism()}.
     */
    @Deprecated(forRemoval = false)
    public int concurrency() {
        return parallelism;
    }

    public static Builder builder(String id) { return new Builder(id); }

    public static final class Builder {
        private final String id;
        private String requestTopic;
        private String responseTopic;
        private int parallelism = 1;
        private PartitionKeyStrategy partitionKeyStrategy = PartitionKeyStrategy.AGENT_RUN;
        private String endpoint = "";
        private String model = "";
        private String apiKeyConfigKey = "";
        private String apiKeyEnvVariable = "";

        private Builder(String id) { this.id = id; }
        public Builder requestTopic(String requestTopic) { this.requestTopic = requestTopic; return this; }
        public Builder responseTopic(String responseTopic) { this.responseTopic = responseTopic; return this; }
        public Builder parallelism(int parallelism) { this.parallelism = parallelism; return this; }
        /**
         * @deprecated Use {@link #parallelism(int)}.
         */
        @Deprecated(forRemoval = false)
        public Builder concurrency(int concurrency) { return parallelism(concurrency); }
        public Builder partitionKeyStrategy(PartitionKeyStrategy partitionKeyStrategy) { this.partitionKeyStrategy = partitionKeyStrategy; return this; }
        public Builder endpoint(String endpoint) { this.endpoint = endpoint; return this; }
        public Builder model(String model) { this.model = model; return this; }
        public Builder apiKeyConfigKey(String apiKeyConfigKey) { this.apiKeyConfigKey = apiKeyConfigKey; return this; }
        public Builder apiKeyEnvVariable(String apiKeyEnvVariable) { this.apiKeyEnvVariable = apiKeyEnvVariable; return this; }
        public InferenceLaneDefinition build() { return new InferenceLaneDefinition(id, requestTopic, responseTopic, parallelism, partitionKeyStrategy, endpoint, model, apiKeyConfigKey, apiKeyEnvVariable); }
    }
}
