package us.nusun.llmshare.framework.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import us.nusun.llmshare.framework.inventory.PersonaCatalog;
import us.nusun.llmshare.framework.inventory.RoutineCatalog;
import us.nusun.llmshare.framework.model.Persona;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.state.AgentRun;
import us.nusun.llmshare.framework.state.AgentRunStore;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class MissionRosterService {
    @Inject AgentRunStore agentRuns;
    @Inject PersonaCatalog personas;
    @Inject RoutineCatalog routines;
    @Inject ObjectMapper objectMapper;

    public MissionRosterService() {
    }

    public MissionRosterService(AgentRunStore agentRuns, PersonaCatalog personas, RoutineCatalog routines, ObjectMapper objectMapper) {
        this.agentRuns = agentRuns;
        this.personas = personas;
        this.routines = routines;
        this.objectMapper = objectMapper;
    }

    public String missionRosterPromptJson(Exchange exchange) {
        AgentRun current = exchange == null ? null : exchange.getProperty("agentRun", AgentRun.class);
        return missionRosterPromptJson(current == null ? null : current.missionId(), current == null ? null : current.id());
    }

    public String missionRosterPromptJson(String missionId, String currentAgentRunId) {
        List<MissionRosterEntry> entries = agentRuns.runsByMission(missionId).stream()
                .map(run -> entry(run, currentAgentRunId))
                .toList();
        try {
            return mapper().writerWithDefaultPrettyPrinter().writeValueAsString(entries);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to render mission roster prompt JSON", e);
        }
    }

    private ObjectMapper mapper() {
        return objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    private MissionRosterEntry entry(AgentRun run, String currentAgentRunId) {
        Persona persona = personas.getRequired(run.personaId());
        RoutineDefinition routine = routines.getRequired(run.routineId());
        return new MissionRosterEntry(
                run.id(),
                run.personaId(),
                persona.name(),
                persona.label(),
                run.routineId(),
                routine.name(),
                routine.description(),
                run.status(),
                run.parentAgentRunId(),
                run.id().equals(currentAgentRunId),
                PersonaCatalog.promptIdentities(persona));
    }

    public record MissionRosterEntry(
            String agentRunId,
            String personaId,
            String personaName,
            String personaLabel,
            String routineId,
            String routineName,
            String routineDescription,
            String status,
            String parentAgentRunId,
            boolean self,
            Map<String, PersonaCatalog.IdentityPromptView> identities
    ) {}
}
