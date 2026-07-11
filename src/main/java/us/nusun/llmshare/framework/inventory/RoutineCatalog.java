package us.nusun.llmshare.framework.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import us.nusun.llmshare.framework.model.RoutineDefinition;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@Named("routineCatalog")
public class RoutineCatalog {
    private final Map<String, RoutineDefinition> routines;
    private final CatalogJson json;

    @Inject
    public RoutineCatalog(Instance<RoutineProvider> providers, ObjectMapper objectMapper) {
        this(providers.stream().flatMap(provider -> provider.routines().stream()).toList(), objectMapper);
    }

    public RoutineCatalog(List<RoutineDefinition> routines) {
        this(routines, new ObjectMapper());
    }

    public RoutineCatalog(List<RoutineDefinition> routines, ObjectMapper objectMapper) {
        this.routines = CatalogIndex.uniqueById(routines, RoutineDefinition::id, "Duplicate routine id: ");
        this.json = new CatalogJson(objectMapper);
    }

    public RoutineDefinition getRequired(String id) {
        RoutineDefinition routine = routines.get(id);
        if (routine == null) {
            throw new IllegalArgumentException("Unknown routine: " + id);
        }
        return routine;
    }

    public List<RoutineDefinition> all() {
        return routines.values().stream().sorted(Comparator.comparing(RoutineDefinition::id)).toList();
    }

    public String allJson() {
        return json(all());
    }

    public String startableJson() {
        return json(all());
    }

    private String json(Object value) {
        return json.write(value, "routine");
    }
}
