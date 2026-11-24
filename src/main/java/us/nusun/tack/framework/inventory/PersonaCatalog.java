package us.nusun.tack.framework.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import us.nusun.tack.framework.model.ExternalIdentity;
import us.nusun.tack.framework.model.Persona;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@Named("personaCatalog")
public class PersonaCatalog {
    private final Map<String, Persona> personas;
    private final CatalogJson json;

    @Inject
    public PersonaCatalog(Instance<PersonaProvider> providers, ObjectMapper objectMapper) {
        this(providers.stream().flatMap(provider -> provider.personas().stream()).toList(), objectMapper);
    }

    public PersonaCatalog(List<Persona> personas) {
        this(personas, new ObjectMapper());
    }

    public PersonaCatalog(List<Persona> personas, ObjectMapper objectMapper) {
        this.personas = CatalogIndex.uniqueById(personas, Persona::id, "Duplicate persona id: ");
        this.json = new CatalogJson(objectMapper);
    }

    public Persona getRequired(String id) {
        Persona persona = personas.get(id);
        if (persona == null) {
            throw new IllegalArgumentException("Unknown persona: " + id);
        }
        return persona;
    }

    public List<Persona> all() {
        return personas.values().stream().sorted(Comparator.comparing(Persona::id)).toList();
    }

    public List<Persona> agentRunStartAssignable() {
        return all().stream().filter(Persona::agentRunStartAssignable).toList();
    }

    public String allJson() {
        return json(all());
    }

    public String agentRunStartAssignableJson() {
        return json(agentRunStartAssignable());
    }

    public String personaInventoryPromptJson() {
        return json(all().stream().map(persona -> promptView(persona, null)).toList());
    }

    public String allPromptJson() {
        return personaInventoryPromptJson();
    }

    public String agentRunStartAssignablePromptJson() {
        return json(agentRunStartAssignable().stream().map(persona -> promptView(persona, null)).toList());
    }

    private static PersonaPromptView promptView(Persona persona, String selfPersonaId) {
        return new PersonaPromptView(
                persona.id(),
                persona.name(),
                persona.label(),
                persona.description(),
                persona.traits(),
                persona.agentRunStartAssignable(),
                persona.id() != null && persona.id().equals(selfPersonaId),
                promptIdentities(persona));
    }

    public static Map<String, IdentityPromptView> promptIdentities(Persona persona) {
        LinkedHashMap<String, IdentityPromptView> safe = new LinkedHashMap<>();
        persona.identities().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    ExternalIdentity identity = entry.getValue();
                    String handle = identity == null ? null : identity.handle();
                    if (handle != null && !handle.isBlank()) {
                        String platform = identity.platform() == null || identity.platform().isBlank() ? entry.getKey() : identity.platform();
                        safe.put(entry.getKey(), new IdentityPromptView(platform, handle));
                    }
                });
        return Map.copyOf(safe);
    }

    private String json(Object value) {
        return json.write(value, "persona");
    }

    public record PersonaPromptView(
            String id,
            String name,
            String label,
            String description,
            List<String> traits,
            boolean agentRunStartAssignable,
            boolean self,
            Map<String, IdentityPromptView> identities
    ) {}

    public record IdentityPromptView(String platform, String handle) {}
}
