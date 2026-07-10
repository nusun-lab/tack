package us.nusun.llmshare.framework.model;

public record RoutineDefinition(
        String id,
        String name,
        String description,
        String routeUri,
        String defaultLane
) {
    public static final String FRAMEWORK_DEFAULT_LANE = "low-pass";

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static final class Builder {
        private final String id;
        private String name;
        private String description;
        private String routeUri;
        private String defaultLane = FRAMEWORK_DEFAULT_LANE;

        private Builder(String id) {
            this.id = id;
        }

        public Builder name(String name) { this.name = name; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder routeUri(String routeUri) { this.routeUri = routeUri; return this; }
        public Builder defaultLane(String defaultLane) { this.defaultLane = defaultLane; return this; }

        public RoutineDefinition build() {
            return new RoutineDefinition(id, name, description, routeUri, defaultLane);
        }
    }
}
