package us.nusun.llmshare.framework.model;

public record ExecutionRuntimeDefinition(
        String id,
        String laneId,
        boolean toolCalling,
        String agentName,
        String agentConfigurationBeanName,
        String agentFactoryBeanName
) {
    public static Builder builder(String id) { return new Builder(id); }

    public static final class Builder {
        private final String id;
        private String laneId;
        private boolean toolCalling;
        private String agentName;
        private String agentConfigurationBeanName;
        private String agentFactoryBeanName;

        private Builder(String id) { this.id = id; this.agentName = id; }
        public Builder lane(String laneId) { this.laneId = laneId; return this; }
        public Builder toolCalling(boolean toolCalling) { this.toolCalling = toolCalling; return this; }
        public Builder agentName(String agentName) { this.agentName = agentName; return this; }
        public Builder agentConfigurationBeanName(String agentConfigurationBeanName) { this.agentConfigurationBeanName = agentConfigurationBeanName; return this; }
        public Builder agentFactoryBeanName(String agentFactoryBeanName) { this.agentFactoryBeanName = agentFactoryBeanName; return this; }
        public ExecutionRuntimeDefinition build() { return new ExecutionRuntimeDefinition(id, laneId, toolCalling, agentName, agentConfigurationBeanName, agentFactoryBeanName); }
    }
}
