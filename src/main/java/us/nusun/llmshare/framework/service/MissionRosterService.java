package us.nusun.llmshare.framework.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.camel.Exchange;
import us.nusun.llmshare.framework.inventory.PersonaCatalog;
import us.nusun.llmshare.framework.inventory.RoutineCatalog;
import us.nusun.llmshare.framework.model.Persona;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.model.SubroutineDefinition;
import us.nusun.llmshare.framework.state.AgentRun;
import us.nusun.llmshare.framework.state.AgentRunStore;

import java.util.Map;
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

    public MissionRosterService(AgentRunStore agentRuns, PersonaCatalog personas, RoutineCatalog routines, ObjectMapper objectMapper) {
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
        return rosterPrompt(missionId, false);
    }

    public String activeMissionRosterPrompt(String missionId) {
        return rosterPrompt(missionId, true);
    }

    private String rosterPrompt(String missionId, boolean activeOnly) {
        String roster = agentRuns.runsByMission(missionId).stream()
                .filter(run -> !activeOnly || AgentRunStore.isActiveStatus(run.status()))
                .map(this::entry)
                .collect(Collectors.joining("\n"));
        return roster.isBlank() ? "(none)" : roster;
    }

    private String entry(AgentRun run) {
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
                run.parentAgentRunId() == null ? "(none)" : run.parentAgentRunId(),
                run.status(),
                renderInput(run.input()),
                renderIdentities(persona)).strip();
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

    private static String renderIdentities(Persona persona) {
        String identities = persona.identities().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .filter(entry -> entry.getValue() != null
                        && entry.getValue().handle() != null
                        && !entry.getValue().handle().isBlank())
                .map(entry -> {
                    String platform = entry.getValue().platform();
                    if (platform == null || platform.isBlank()) {
                        platform = entry.getKey();
                    }
                    return platform + " " + entry.getValue().handle();
                })
                .collect(Collectors.joining(", "));
        return identities.isBlank() ? "(none)" : identities;
    }
}
