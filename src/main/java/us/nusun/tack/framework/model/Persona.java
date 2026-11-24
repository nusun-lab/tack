package us.nusun.tack.framework.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record Persona(
        String id,
        String name,
        String email,
        String description,
        List<String> traits,
        String label,
        String color,
        boolean agentRunStartAssignable,
        Map<String, ExternalIdentity> identities
) {
    public Persona {
        traits = traits == null ? List.of() : List.copyOf(traits);
        identities = identities == null ? Map.of() : Map.copyOf(identities);
    }

    public ExternalIdentity identityFor(String platform) {
        return identities.get(platform);
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static final class Builder {
        private final String id;
        private String name;
        private String email;
        private String description;
        private final List<String> traits = new ArrayList<>();
        private String label;
        private String color;
        private boolean agentRunStartAssignable = true;
        private final Map<String, ExternalIdentity> identities = new LinkedHashMap<>();

        private Builder(String id) {
            this.id = id;
        }

        public Builder name(String name) { this.name = name; return this; }
        public Builder email(String email) { this.email = email; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder trait(String trait) { this.traits.add(trait); return this; }
        public Builder traits(List<String> traits) { this.traits.addAll(traits); return this; }
        public Builder label(String label) { this.label = label; return this; }
        public Builder color(String color) { this.color = color; return this; }
        public Builder agentRunStartAssignable(boolean agentRunStartAssignable) {
            this.agentRunStartAssignable = agentRunStartAssignable;
            return this;
        }
        public Builder identity(String platform, String token) {
            this.identities.put(platform, new ExternalIdentity(platform, token, null));
            return this;
        }

        public Builder identity(String platform, String token, String handle) {
            this.identities.put(platform, new ExternalIdentity(platform, token, handle));
            return this;
        }

        public Persona build() {
            return new Persona(id, name, email, description, traits, label, color, agentRunStartAssignable, identities);
        }
    }
}
