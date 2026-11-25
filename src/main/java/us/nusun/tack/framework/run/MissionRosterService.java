package us.nusun.tack.framework.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import us.nusun.tack.framework.persona.PersonaCatalog;
import us.nusun.tack.framework.routine.RoutineCatalog;
import us.nusun.tack.framework.persona.Persona;
import us.nusun.tack.framework.persona.PersonaView;
import us.nusun.tack.framework.routine.RoutineDefinition;
import us.nusun.tack.framework.routine.SubroutineDefinition;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
@Named("missionRosterService")
public class MissionRosterService {
    private final AgentRunStore agentRuns;
    private final PersonaCatalog personas;
    private final RoutineCatalog routines;
    private final ObjectMapper objectMapper;

    public MissionRosterService(AgentRunStore agentRuns, PersonaCatalog personas,
                               RoutineCatalog routines, ObjectMapper objectMapper) {
        this.agentRuns = agentRuns;
        this.personas = personas;
        this.routines = routines;
        this.objectMapper = objectMapper;
    }

    public String rosterView(
            String missionId,
            String scope,
            String view,
            String personaId,
            String routineId,
            String platforms,
            Integer capacityMaximum) {
        String normalizedScope = normalizeScope(scope);
        String normalizedView = normalizeView(view, normalizedScope);
        Set<String> platformFilter = csvSet(platforms);

        String selectedPersona = personaId == null ? "" : personaId.strip();
        String selectedRoutine = routineId == null ? "" : routineId.strip();
        var candidates = agentRuns.runsByMission(missionId).stream()
                .filter(run -> !"active".equals(normalizedScope) || AgentRunStore.isActiveStatus(run.status()))
                .filter(run -> selectedPersona.isEmpty() || selectedPersona.equals(run.personaId()))
                .filter(run -> selectedRoutine.isEmpty() || selectedRoutine.equals(run.routineId()));
        if ("history".equals(normalizedScope)) {
            candidates = candidates.sorted(Comparator.comparing(AgentRun::startedAt).reversed());
        }
        List<AgentRun> runs = candidates.toList();

        String roster = "contacts".equals(normalizedView)
                ? contactsView(runs, platformFilter)
                : runs.stream()
                        .map(run -> renderView(run, normalizedView, platformFilter))
                        .collect(Collectors.joining("\n"));
        if ("coverage".equals(normalizedView)) {
            roster = capacitySummary(missionId, capacityMaximum) + (roster.isBlank() ? "" : "\n" + roster);
        }
        return roster.isBlank() ? "(none)" : roster;
    }

    private String renderView(AgentRun run, String view, Set<String> platforms) {
        return switch (view) {
            case "coverage" -> coverageEntry(run, personas.getRequired(run.personaId()));
            case "runs" -> runsEntry(run, platforms);
            case "full" -> fullViewEntry(run, platforms);
            default -> throw new IllegalArgumentException("Unsupported roster view: " + view);
        };
    }

    private String contactsView(List<AgentRun> runs, Set<String> platforms) {
        Map<String, List<AgentRun>> byPersona = runs.stream()
                .collect(Collectors.groupingBy(
                        AgentRun::personaId,
                        LinkedHashMap::new,
                        Collectors.toList()));
        return byPersona.values().stream()
                .map(personaRuns -> contactsEntry(personaRuns, platforms))
                .collect(Collectors.joining("\n"));
    }

    private String capacitySummary(String missionId, Integer maximum) {
        int active = agentRuns.activeRunCount(missionId);
        if (maximum == null) {
            return "capacity: active=%d maximum=(not configured) remaining=(not configured)".formatted(active);
        }
        int remaining = Math.max(0, maximum - active);
        return "capacity: active=%d maximum=%d remaining=%d".formatted(active, maximum, remaining);
    }

    private String coverageEntry(AgentRun run, Persona persona) {
        return "- %s | personaId=%s | routineId=%s | target=%s | status=%s".formatted(
                persona.name(),
                run.personaId(),
                run.routineId(),
                target(run),
                run.status());
    }

    private String contactsEntry(List<AgentRun> runs, Set<String> platforms) {
        AgentRun first = runs.get(0);
        Persona persona = personas.getRequired(first.personaId());
        String routineIds = runs.stream()
                .map(AgentRun::routineId)
                .distinct()
                .sorted()
                .collect(Collectors.joining(","));
        String statuses = runs.stream()
                .map(AgentRun::status)
                .distinct()
                .sorted()
                .collect(Collectors.joining(","));
        return "- %s | personaId=%s | routineIds=%s | statuses=%s | handles: %s".formatted(
                persona.name(),
                first.personaId(),
                routineIds,
                statuses,
                renderIdentities(persona, platforms));
    }

    private String runsEntry(AgentRun run, Set<String> platforms) {
        Persona persona = personas.getRequired(run.personaId());
        String entry = coverageEntry(run, persona)
                + " | agentRunId=" + run.id()
                + " | parentAgentRunId=" + value(run.parentAgentRunId())
                + " | startedAt=" + run.startedAt();
        if (!platforms.isEmpty()) {
            entry += " | handles: " + renderIdentities(persona, platforms);
        }
        return entry;
    }

    private String fullViewEntry(AgentRun run, Set<String> platforms) {
        Persona persona = personas.getRequired(run.personaId());
        RoutineDefinition routine = routines.getRequired(run.routineId());
        SubroutineDefinition subroutine = run.subroutineId() == null ? null : routine.requireSubroutine(run.subroutineId());
        return """
                - name: %s
                  personaId: %s
                  routine: %s
                  routineId: %s
                  subroutineId: %s
                  subroutine: %s
                  target: %s
                  agentRunId: %s
                  parentAgentRunId: %s
                  status: %s
                  startedAt: %s
                  input: %s
                  platform identities: %s
                """.formatted(
                persona.name(),
                run.personaId(),
                routine.name(),
                run.routineId(),
                subroutine == null ? "(full routine)" : subroutine.id(),
                subroutine == null ? "(full routine)" : subroutine.description(),
                subroutine == null ? "full routine" : "subroutine",
                run.id(),
                value(run.parentAgentRunId()),
                run.status(),
                run.startedAt(),
                renderInput(run.input()),
                renderIdentities(persona, platforms)).strip();
    }

    private String renderInput(Object input) {
        if (input == null) {
            return "(none)";
        }
        if (input instanceof CharSequence text) {
            return text.isEmpty() ? "(empty text)" : text.toString();
        }
        try {
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            return String.valueOf(input);
        }
    }

    private static String renderIdentities(Persona persona, Set<String> platforms) {
        String identities = PersonaView.identitiesOf(persona).entrySet().stream()
                .filter(entry -> !blank(entry.getValue().handle()))
                .filter(entry -> platforms.isEmpty() || platforms.contains(normalize(entry.getKey()))
                        || platforms.contains(normalize(entry.getValue().platform())))
                .map(entry -> entry.getValue().platform() + "=" + entry.getValue().handle())
                .collect(Collectors.joining(", "));
        return identities.isBlank() ? "(none)" : identities;
    }

    private static String target(AgentRun run) {
        return run.subroutineId() == null ? "main" : "subroutine:" + run.subroutineId();
    }

    private static String normalizeScope(String scope) {
        if (blank(scope)) {
            return "active";
        }
        return switch (normalize(scope)) {
            case "active", "current" -> "active";
            case "history", "all" -> "history";
            default -> throw new IllegalArgumentException("scope must be active or history");
        };
    }

    private static String normalizeView(String view, String scope) {
        if (blank(view)) {
            return "history".equals(scope) ? "runs" : "contacts";
        }
        return switch (normalize(view)) {
            case "coverage" -> "coverage";
            case "contacts", "contact", "handles", "identities" -> "contacts";
            case "runs", "run" -> "runs";
            case "full", "detail", "details" -> "full";
            default -> throw new IllegalArgumentException("view must be coverage, contacts, runs, or full");
        };
    }

    private static Set<String> csvSet(String value) {
        if (blank(value)) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(MissionRosterService::normalize)
                .filter(item -> !item.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private static String value(String value) {
        return blank(value) ? "(none)" : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
