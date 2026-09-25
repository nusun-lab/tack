package us.nusun.llmshare.framework.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.camel.Exchange;
import us.nusun.llmshare.framework.inventory.PersonaCatalog;
import us.nusun.llmshare.framework.inventory.RoutineCatalog;
import us.nusun.llmshare.framework.model.ExternalIdentity;
import us.nusun.llmshare.framework.model.Persona;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.model.SubroutineDefinition;
import us.nusun.llmshare.framework.state.AgentRun;
import us.nusun.llmshare.framework.state.AgentRunStore;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
@Named("missionRosterService")
public class MissionRosterService {
    @Inject AgentRunStore agentRuns;
    @Inject PersonaCatalog personas;
    @Inject RoutineCatalog routines;
    @Inject ObjectMapper objectMapper;

    public MissionRosterService() {
    }

    public MissionRosterService(AgentRunStore agentRuns, PersonaCatalog personas, RoutineCatalog routines) {
        this(agentRuns, personas, routines, new ObjectMapper());
    }

    public MissionRosterService(
            AgentRunStore agentRuns,
            PersonaCatalog personas,
            RoutineCatalog routines,
            ObjectMapper objectMapper) {
        this.agentRuns = agentRuns;
        this.personas = personas;
        this.routines = routines;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public String missionRosterPrompt(Exchange exchange) {
        AgentRun current = exchange == null ? null : exchange.getProperty("agentRun", AgentRun.class);
        return missionRosterPrompt(current == null ? null : current.missionId());
    }

    public String activeMissionRosterPrompt(Exchange exchange) {
        AgentRun current = exchange == null ? null : exchange.getProperty("agentRun", AgentRun.class);
        return activeMissionRosterPrompt(current == null ? null : current.missionId());
    }

    public String missionRosterPrompt(String missionId) {
        return legacyRosterPrompt(missionId, false);
    }

    public String activeMissionRosterPrompt(String missionId) {
        return legacyRosterPrompt(missionId, true);
    }

    public String rosterView(
            String missionId,
            String scope,
            String view,
            String personaId,
            String routineId,
            String platforms) {
        String normalizedScope = normalizeScope(scope);
        String normalizedView = normalizeView(view, normalizedScope);
        Set<String> platformFilter = csvSet(platforms);

        List<AgentRun> runs = agentRuns.runsByMission(missionId).stream()
                .filter(run -> !"active".equals(normalizedScope) || AgentRunStore.isActiveStatus(run.status()))
                .filter(run -> blank(personaId) || personaId.strip().equals(run.personaId()))
                .filter(run -> blank(routineId) || routineId.strip().equals(run.routineId()))
                .sorted("history".equals(normalizedScope)
                        ? Comparator.comparing(AgentRun::startedAt).reversed()
                        : Comparator.comparing(AgentRun::id))
                .toList();

        String roster = "contacts".equals(normalizedView)
                ? contactsView(runs, platformFilter)
                : runs.stream()
                        .map(run -> renderView(run, normalizedView, platformFilter))
                        .collect(Collectors.joining("\n"));
        return roster.isBlank() ? "(none)" : roster;
    }

    private String legacyRosterPrompt(String missionId, boolean activeOnly) {
        String roster = agentRuns.runsByMission(missionId).stream()
                .filter(run -> !activeOnly || AgentRunStore.isActiveStatus(run.status()))
                .map(this::fullEntry)
                .collect(Collectors.joining("\n"));
        return roster.isBlank() ? "(none)" : roster;
    }

    private String renderView(AgentRun run, String view, Set<String> platforms) {
        return switch (view) {
            case "coverage" -> coverageEntry(run);
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

    private String coverageEntry(AgentRun run) {
        Persona persona = personas.getRequired(run.personaId());
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
        String entry = coverageEntry(run)
                + " | agentRunId=" + run.id()
                + " | parentAgentRunId=" + value(run.parentAgentRunId())
                + " | startedAt=" + run.startedAt();
        if (!platforms.isEmpty()) {
            entry += " | handles: " + renderIdentities(personas.getRequired(run.personaId()), platforms);
        }
        return entry;
    }

    private String fullEntry(AgentRun run) {
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
                renderInput(run.input()),
                renderLegacyIdentities(persona)).strip();
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

    private static String renderLegacyIdentities(Persona persona) {
        String identities = persona.identities().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .filter(entry -> hasHandle(entry.getValue()))
                .map(entry -> {
                    String platform = entry.getValue().platform();
                    if (blank(platform)) {
                        platform = entry.getKey();
                    }
                    return platform + " " + entry.getValue().handle();
                })
                .collect(Collectors.joining(", "));
        return identities.isBlank() ? "(none)" : identities;
    }

    private static String renderIdentities(Persona persona, Set<String> platforms) {
        String identities = persona.identities().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .filter(entry -> hasHandle(entry.getValue()))
                .filter(entry -> platforms.isEmpty() || matchesPlatform(platforms, entry))
                .map(entry -> {
                    String platform = entry.getValue().platform();
                    if (blank(platform)) {
                        platform = entry.getKey();
                    }
                    return platform + "=" + entry.getValue().handle();
                })
                .collect(Collectors.joining(", "));
        return identities.isBlank() ? "(none)" : identities;
    }

    private static boolean matchesPlatform(Set<String> platforms, Map.Entry<String, ExternalIdentity> entry) {
        String key = normalize(entry.getKey());
        String platform = normalize(entry.getValue() == null ? null : entry.getValue().platform());
        return platforms.contains(key) || platforms.contains(platform);
    }

    private static boolean hasHandle(ExternalIdentity identity) {
        return identity != null && !blank(identity.handle());
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
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String item : value.split(",")) {
            String normalized = normalize(item);
            if (!normalized.isBlank()) {
                result.add(normalized);
            }
        }
        return Set.copyOf(result);
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
