package us.nusun.tack.framework.persona;

import us.nusun.tack.framework.CatalogIndex;
import us.nusun.tack.framework.CatalogJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.Comparator;
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

    public List<PersonaView> views() {
        return all().stream().map(PersonaView::of).toList();
    }

    public String agentRunStartAssignablePromptJson() {
        return json.write(views().stream().filter(PersonaView::agentRunStartAssignable).toList(), "persona");
    }
}
