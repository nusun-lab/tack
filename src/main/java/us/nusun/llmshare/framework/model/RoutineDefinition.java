package us.nusun.llmshare.framework.model;

public record RoutineDefinition(
        String id,
        String name,
        String description,
        String routeUri,
        String defaultToolRuntime,
        String defaultSynthesisRuntime
) {
    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static final class Builder {
        private final String id;
        private String name;
        private String description;
        private String routeUri;
        private String defaultToolRuntime = "tool-low";
        private String defaultSynthesisRuntime = "synthesis-low";

        private Builder(String id) {
            this.id = id;
        }

        public Builder name(String name) { this.name = name; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder routeUri(String routeUri) { this.routeUri = routeUri; return this; }
        public Builder defaultToolRuntime(String defaultToolRuntime) { this.defaultToolRuntime = defaultToolRuntime; return this; }
        public Builder defaultSynthesisRuntime(String defaultSynthesisRuntime) { this.defaultSynthesisRuntime = defaultSynthesisRuntime; return this; }

        public RoutineDefinition build() {
            return new RoutineDefinition(id, name, description, routeUri, defaultToolRuntime, defaultSynthesisRuntime);
        }
    }
}
