package us.nusun.llmshare.framework.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.model.SubroutineDefinition;
import us.nusun.llmshare.framework.state.AgentRun;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@Named("routineCatalog")
public class RoutineCatalog {
    private static final String MAIN_COMPOSITION_GUIDANCE = """
            The main routine composes the listed subroutines into the complete workflow and is the preferred entry point
            for self-directed execution. Subroutines are focused slices for explicitly narrow work.
            """.strip();

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


    public SubroutineDefinition requireSubroutine(String routineId, String subroutineId) {
        return getRequired(routineId).requireSubroutine(subroutineId);
    }

    public String routeUriFor(AgentRun run) {
        if (run == null) {
            throw new IllegalArgumentException("AgentRun route resolution requires run");
        }
        return getRequired(run.routineId()).routeUri(run.subroutineId());
    }

    public List<RoutineDefinition> all() {
        return routines.values().stream().sorted(Comparator.comparing(RoutineDefinition::id)).toList();
    }

    public String allJson() {
        return json(all());
    }

    public String startableJson() {
        List<RoutineDefinition> advertised = all().stream()
                .map(this::advertise)
                .toList();
        return json(advertised);
    }

    private RoutineDefinition advertise(RoutineDefinition routine) {
        if (routine.subroutines().isEmpty()) {
            return routine;
        }
        return new RoutineDefinition(
                routine.id(),
                routine.name(),
                routine.description() + "\n\n" + MAIN_COMPOSITION_GUIDANCE,
                routine.routeUri(),
                routine.defaultLane(),
                routine.subroutines());
    }

    private String json(Object value) {
        return json.write(value, "routine");
    }
}
