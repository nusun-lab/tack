package us.nusun.tack.framework.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record PersonaView(
        String id,
        String name,
        String email,
        String description,
        List<String> traits,
        String label,
        String color,
        boolean agentRunStartAssignable,
        Map<String, Identity> identities
) {
    public static PersonaView of(Persona persona) {
        return new PersonaView(persona.id(), persona.name(), persona.email(), persona.description(), persona.traits(),
                persona.label(), persona.color(), persona.agentRunStartAssignable(), identitiesOf(persona));
    }

    public static Map<String, Identity> identitiesOf(Persona persona) {
        Map<String, Identity> identities = new LinkedHashMap<>();
        persona.identities().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            var identity = entry.getValue();
            String platform = identity.platform();
            identities.put(entry.getKey(), new Identity(
                    platform == null || platform.isBlank() ? entry.getKey() : platform, identity.handle()));
        });
        return Collections.unmodifiableMap(identities);
    }

    public record Identity(String platform, String handle) {}
}
