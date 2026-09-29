package us.nusun.llmshare.framework.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record RoutineDefinition(
        String id,
        String name,
        String description,
        String routeUri,
        String defaultLane,
        Map<LlmPassType, String> laneBindings,
        List<SubroutineDefinition> subroutines
) {
    public static final String FRAMEWORK_DEFAULT_LANE = "low-pass";

    public RoutineDefinition(
            String id,
            String name,
            String description,
            String routeUri,
            String defaultLane,
            List<SubroutineDefinition> subroutines) {
        this(id, name, description, routeUri, defaultLane, Map.of(), subroutines);
    }

    public RoutineDefinition {
        id = requireText(id, "Routine missing id");
        name = requireText(name, "Routine " + id + " missing name");
        description = requireText(description, "Routine " + id + " missing description");
        routeUri = requireText(routeUri, "Routine " + id + " missing routeUri");
        defaultLane = defaultLane == null || defaultLane.isBlank() ? FRAMEWORK_DEFAULT_LANE : defaultLane.trim();
        laneBindings = normalizeLaneBindings(laneBindings);
        subroutines = subroutines == null ? List.of() : List.copyOf(subroutines);
        validateUniqueSubroutineIds(subroutines);
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public String laneFor(LlmPassType passType) {
        String boundLane = passType == null ? null : laneBindings.get(passType);
        return boundLane == null ? defaultLane : boundLane;
    }

    public SubroutineDefinition requireSubroutine(String subroutineId) {
        String id = normalizeSubroutineId(subroutineId);
        if (id == null) {
            throw new IllegalArgumentException("Missing subroutine id for routine " + this.id);
        }
        return subroutines.stream()
                .filter(subroutine -> subroutine.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown subroutine " + id + " for routine " + this.id));
    }

    public String routeUri(String subroutineId) {
        String id = normalizeSubroutineId(subroutineId);
        return id == null ? routeUri : requireSubroutine(id).routeUri();
    }

    public static String normalizeSubroutineId(String subroutineId) {
        return subroutineId == null || subroutineId.isBlank() ? null : subroutineId.trim();
    }

    private static Map<LlmPassType, String> normalizeLaneBindings(Map<LlmPassType, String> laneBindings) {
        if (laneBindings == null || laneBindings.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<LlmPassType, String> normalized = new LinkedHashMap<>();
        laneBindings.forEach((passType, lane) -> {
            if (passType == null) {
                throw new IllegalArgumentException("Routine lane binding missing pass type");
            }
            normalized.put(passType, requireText(lane, "Routine lane binding for " + passType + " missing lane"));
        });
        return Map.copyOf(normalized);
    }

    private static void validateUniqueSubroutineIds(List<SubroutineDefinition> subroutines) {
        LinkedHashMap<String, Boolean> ids = new LinkedHashMap<>();
        for (SubroutineDefinition subroutine : subroutines) {
            if (ids.put(subroutine.id(), Boolean.TRUE) != null) {
                throw new IllegalArgumentException("Duplicate subroutine id: " + subroutine.id());
            }
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    public static final class Builder {
        private final String id;
        private String name;
        private String description;
        private String routeUri;
        private String defaultLane = FRAMEWORK_DEFAULT_LANE;
        private final LinkedHashMap<LlmPassType, String> laneBindings = new LinkedHashMap<>();
        private final ArrayList<SubroutineDefinition> subroutines = new ArrayList<>();

        private Builder(String id) {
            this.id = id;
        }

        public Builder name(String name) { this.name = name; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder routeUri(String routeUri) { this.routeUri = routeUri; return this; }

        public Builder main(String description, String routeUri) {
            this.description = description;
            this.routeUri = routeUri;
            return this;
        }

        public Builder defaultLane(String defaultLane) { this.defaultLane = defaultLane; return this; }

        public Builder withLaneBinding(LlmPassType passType, String lane) {
            if (passType == null) {
                throw new IllegalArgumentException("Routine lane binding missing pass type");
            }
            laneBindings.put(passType, requireText(lane, "Routine lane binding for " + passType + " missing lane"));
            return this;
        }

        public Builder subroutine(String id, String description, String routeUri) {
            return subroutine(new SubroutineDefinition(id, description, routeUri));
        }

        public Builder subroutine(SubroutineDefinition subroutine) {
            if (subroutine == null) {
                throw new IllegalArgumentException("Routine subroutine cannot be null");
            }
            subroutines.add(subroutine);
            return this;
        }

        public RoutineDefinition build() {
            return new RoutineDefinition(id, name, description, routeUri, defaultLane, laneBindings, subroutines);
        }
    }
}
