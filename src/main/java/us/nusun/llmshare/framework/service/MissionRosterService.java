package us.nusun.llmshare.framework.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import us.nusun.llmshare.framework.inventory.PersonaCatalog;
import us.nusun.llmshare.framework.inventory.RoutineCatalog;
import us.nusun.llmshare.framework.model.Persona;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.state.AgentRun;
import us.nusun.llmshare.framework.state.AgentRunStore;

import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class MissionRosterService {
    @Inject AgentRunStore agentRuns;
    @Inject PersonaCatalog personas;
    @Inject RoutineCatalog routines;

    public MissionRosterService() {
    }

    public MissionRosterService(AgentRunStore agentRuns, PersonaCatalog personas, RoutineCatalog routines) {
        this.agentRuns = agentRuns;
        this.personas = personas;
        this.routines = routines;
    }

    public String missionRosterPrompt(Exchange exchange) {
        AgentRun current = exchange == null ? null : exchange.getProperty("agentRun", AgentRun.class);
        return missionRosterPrompt(current == null ? null : current.missionId());
    }

    public String missionRosterPrompt(String missionId) {
        String roster = agentRuns.runsByMission(missionId).stream()
                .map(this::entry)
                .collect(Collectors.joining("\n"));
        return roster.isBlank() ? "(none)" : roster;
    }

    private String entry(AgentRun run) {
        Persona persona = personas.getRequired(run.personaId());
        RoutineDefinition routine = routines.getRequired(run.routineId());
        return """
                - name: %s
                  personaId: %s
                  routine: %s
                  routineId: %s
                  agentRunId: %s
                  status: %s
                  platform identities: %s
                """.formatted(
                persona.name(),
                run.personaId(),
                routine.name(),
                run.routineId(),
                run.id(),
                run.status(),
                renderIdentities(persona)).strip();
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
