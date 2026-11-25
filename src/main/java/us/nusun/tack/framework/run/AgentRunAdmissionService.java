package us.nusun.tack.framework.run;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Body;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangeProperty;
import org.apache.camel.Header;
import us.nusun.tack.framework.persona.PersonaCatalog;
import us.nusun.tack.framework.routine.RoutineCatalog;
import us.nusun.tack.framework.sandbox.PersonaSandboxManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class AgentRunAdmissionService {
    public static final String CAPACITY_MAXIMUM_PROPERTY = "agentRunCapacityMaximum";

    @Inject PersonaCatalog personas;
    @Inject RoutineCatalog routines;
    @Inject AgentRunProjectionManager projections;
    @Inject AgentRunStore agentRuns;
    @Inject PersonaSandboxManager sandboxes;

    public List<AgentRunHandle> admitAll(
            @Body List<AgentRunRequest> requests,
            @ExchangeProperty("mission") Mission mission,
            @ExchangeProperty("agentRun") AgentRun parent,
            Exchange exchange) {
        if (mission == null) {
            throw new IllegalArgumentException("AgentRun admission requires mission exchange property");
        }
        return admit(mission, parent, requests, capacityMaximum(exchange));
    }

    public List<AgentRunHandle> admitMission(@Body MissionRequest request, @Header("requestId") String requestedId,
                                             Exchange exchange) {
        if (request == null) {
            throw new IllegalArgumentException("Mission request is required");
        }
        if (request.goal() == null || request.goal().isBlank()) {
            throw new IllegalArgumentException("Mission request missing goal");
        }
        if (request.agentRuns() == null || request.agentRuns().isEmpty()) {
            throw new IllegalArgumentException("Mission request requires at least one AgentRunRequest");
        }

        Mission mission = new Mission(
                requestedId == null || requestedId.isBlank() ? "mission-" + UUID.randomUUID() : requestedId,
                request.goal(),
                List.of(),
                "active",
                Instant.now());
        List<AgentRunHandle> handles = admit(mission, null, request.agentRuns(), capacityMaximum(exchange));
        exchange.setProperty("mission", mission);
        return handles;
    }

    private List<AgentRunHandle> admit(Mission mission, AgentRun parent, List<AgentRunRequest> requests, Integer maximum) {
        if (requests == null) {
            throw new IllegalArgumentException("AgentRun admission requires AgentRunRequest list");
        }
        List<AgentRun> runs = requests.stream().map(request -> prepareRun(mission, parent, request)).toList();

        synchronized (agentRuns) {
            validateCapacity(mission, runs.size(), maximum);
            if (sandboxes != null) {
                for (AgentRun run : runs) {
                    sandboxes.ensureReady(mission, personas.getRequired(run.personaId()), run);
                }
            }
            agentRuns.putMission(mission);
            for (AgentRun run : runs) {
                run.status(AgentRun.STATUS_ACCEPTED);
                agentRuns.putRun(run);
                projections.recordAccepted(mission, run);
            }
            return runs.stream().map(AgentRunAdmissionService::acceptedHandle).toList();
        }
    }

    private AgentRun prepareRun(Mission mission, AgentRun parent, AgentRunRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("AgentRun request is required");
        }

        personas.getRequired(request.personaId());
        var routine = routines.getRequired(request.routineId());
        if (request.subroutineId() != null) {
            routine.requireSubroutine(request.subroutineId());
        }

        return new AgentRun(
                "run-" + UUID.randomUUID(),
                mission.id(),
                parent == null ? null : parent.id(),
                request.personaId(),
                request.routineId(),
                request.subroutineId(),
                request.workingState());
    }

    private void validateCapacity(Mission mission, int requested, Integer maximum) {
        if (maximum == null || requested == 0) {
            return;
        }
        if (maximum < 0) {
            throw new IllegalArgumentException(CAPACITY_MAXIMUM_PROPERTY + " must be non-negative");
        }
        int active = agentRuns.activeRunCount(mission.id());
        int remaining = Math.max(0, maximum - active);
        if (requested > remaining) {
            throw new IllegalStateException("Mission " + mission.id() + " active run capacity exceeded: maximum "
                    + maximum + ", active " + active + ", requested " + requested);
        }
    }

    private static Integer capacityMaximum(Exchange exchange) {
        if (exchange == null) {
            return null;
        }
        Object value = exchange.getProperty(CAPACITY_MAXIMUM_PROPERTY);
        if (value == null) {
            value = exchange.getIn().getHeader(CAPACITY_MAXIMUM_PROPERTY);
        }
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = String.valueOf(value);
        if (text.isBlank()) {
            return null;
        }
        return Integer.parseInt(text.trim());
    }

    public Map<String, Object> acceptedMissionResponse(@ExchangeProperty("mission") Mission mission,
                                                       @Body List<AgentRunHandle> handles) {
        return Map.of("missionId", mission.id(), "status", "accepted", "agentRuns", handles);
    }

    private static AgentRunHandle acceptedHandle(AgentRun run) {
        return new AgentRunHandle(run.id(), run.missionId(), run.parentAgentRunId(), run.personaId(),
                run.routineId(), run.subroutineId(), AgentRun.STATUS_ACCEPTED);
    }
}
