package us.nusun.llmshare.framework.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import us.nusun.llmshare.framework.model.Persona;

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
        LinkedHashMap<String, Persona> rows = new LinkedHashMap<>();
        for (Persona persona : personas) {
            if (rows.put(persona.id(), persona) != null) {
                throw new IllegalArgumentException("Duplicate persona id: " + persona.id());
            }
        }
        this.personas = Map.copyOf(rows);
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

    public String rosterPrompt() {
        return agentRunStartAssignable().stream()
                .map(persona -> "- " + persona.id() + " (" + persona.name() + "): " + persona.description())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    public String allJson() {
        return json(all());
    }

    public String agentRunStartAssignableJson() {
        return json(agentRunStartAssignable());
    }

    private String json(Object value) {
        return json.write(value, "persona");
    }
}
