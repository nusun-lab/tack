package us.nusun.tack.framework.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Body;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangeProperty;
import org.apache.camel.Header;
import us.nusun.tack.framework.inventory.PersonaCatalog;
import us.nusun.tack.framework.inventory.RoutineCatalog;
import us.nusun.tack.framework.model.AgentRunAdmission;
import us.nusun.tack.framework.model.AgentRunHandle;
import us.nusun.tack.framework.model.AgentRunRequest;
import us.nusun.tack.framework.model.AgentRunScheduled;
import us.nusun.tack.framework.model.Mission;
import us.nusun.tack.framework.model.MissionRequest;
import us.nusun.tack.framework.projection.AgentRunProjectionManager;
import us.nusun.tack.framework.sandbox.PersonaSandboxManager;
import us.nusun.tack.framework.state.AgentRun;
import us.nusun.tack.framework.state.AgentRunStore;

import java.time.Instant;
import java.util.ArrayList;
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

    public AgentRunAdmission admit(
            @Body AgentRunRequest request,
            @ExchangeProperty("mission") Mission mission,
            @ExchangeProperty("agentRun") AgentRun parent,
            Exchange exchange) {
        return admitAll(List.of(request), mission, parent, exchange).getFirst();
    }

    public List<AgentRunAdmission> admitAll(
            @Body List<AgentRunRequest> requests,
            @ExchangeProperty("mission") Mission mission,
            @ExchangeProperty("agentRun") AgentRun parent,
            Exchange exchange) {
        if (mission == null) {
            throw new IllegalArgumentException("AgentRun admission requires mission exchange property");
        }
        if (requests == null) {
            throw new IllegalArgumentException("AgentRun admission requires AgentRunRequest list");
        }
        if (requests.isEmpty()) {
            agentRuns.putMission(mission);
            return List.of();
        }

        List<PreparedAgentRun> prepared = requests.stream()
                .map(request -> prepareAgentRun(mission, parent, request))
                .toList();
        return commitPrepared(mission, prepared, capacityMaximum(exchange));
    }

    public MissionAdmissionPlan prepareMission(@Body MissionRequest request, @Header("requestId") String requestedId) {
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
        List<PreparedAgentRun> prepared = request.agentRuns().stream()
                .map(agentRunRequest -> prepareAgentRun(mission, null, agentRunRequest))
                .toList();
        return new MissionAdmissionPlan(mission, prepared);
    }

    public List<AgentRunAdmission> commitMission(@Body MissionAdmissionPlan plan, Exchange exchange) {
        if (plan == null) {
            throw new IllegalArgumentException("Mission admission plan is required");
        }
        return commitPrepared(plan.mission(), plan.agentRuns(), capacityMaximum(exchange));
    }

    private PreparedAgentRun prepareAgentRun(Mission mission, AgentRun parent, AgentRunRequest request) {
        if (mission == null) {
            throw new IllegalArgumentException("AgentRun preparation requires mission");
        }
        if (request == null) {
            throw new IllegalArgumentException("AgentRun request is required");
        }

        personas.getRequired(request.personaId());
        var routine = routines.getRequired(request.routineId());
        if (request.subroutineId() != null) {
            routine.requireSubroutine(request.subroutineId());
        }

        AgentRun run = new AgentRun(
                "run-" + UUID.randomUUID(),
                mission.id(),
                parent == null ? null : parent.id(),
                request.personaId(),
                request.routineId(),
                request.subroutineId(),
                request.workingState());
        run.status(AgentRun.STATUS_ACCEPTED);

        return new PreparedAgentRun(
                run,
                handle(run, AgentRun.STATUS_ACCEPTED),
                new AgentRunScheduled(run.missionId(), run.id()));
    }

    private List<AgentRunAdmission> commitPrepared(Mission mission, List<PreparedAgentRun> preparedRuns, Integer capacityMaximum) {
        List<AgentRunAdmission> admissions = new ArrayList<>();
        synchronized (agentRuns) {
            validateCapacity(mission, preparedRuns, capacityMaximum);
            if (sandboxes != null) {
                for (PreparedAgentRun prepared : preparedRuns) {
                    sandboxes.ensureReady(mission, personas.getRequired(prepared.run().personaId()), prepared.run());
                }
            }
            agentRuns.putMission(mission);
            for (PreparedAgentRun prepared : preparedRuns) {
                agentRuns.putRun(prepared.run());
                projections.recordAccepted(mission, prepared.run());
                admissions.add(new AgentRunAdmission(prepared.handle(), prepared.event()));
            }
        }
        return List.copyOf(admissions);
    }

    private void validateCapacity(Mission mission, List<PreparedAgentRun> preparedRuns, Integer maximum) {
        if (maximum == null || preparedRuns == null || preparedRuns.isEmpty()) {
            return;
        }
        if (maximum < 0) {
            throw new IllegalArgumentException(CAPACITY_MAXIMUM_PROPERTY + " must be non-negative");
        }
        int active = agentRuns.activeRunCount(mission.id());
        int remaining = Math.max(0, maximum - active);
        if (preparedRuns.size() > remaining) {
            throw new IllegalStateException("Mission " + mission.id() + " active run capacity exceeded: maximum "
                    + maximum + ", active " + active + ", requested " + preparedRuns.size());
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

    public List<AgentRunHandle> handles(@Body List<AgentRunAdmission> admissions) {
        if (admissions == null || admissions.isEmpty()) {
            return List.of();
        }
        return admissions.stream().map(AgentRunAdmission::handle).toList();
    }

    public Map<String, Object> acceptedMissionResponse(
            @ExchangeProperty("missionAdmissionPlan") MissionAdmissionPlan plan,
            @ExchangeProperty("agentRunHandles") List<AgentRunHandle> handles) {
        return Map.of(
                "missionId", plan.mission().id(),
                "status", "accepted",
                "agentRuns", handles == null ? List.of() : List.copyOf(handles));
    }

    public static AgentRunHandle handle(AgentRun run, String status) {
        return new AgentRunHandle(
                run == null ? "" : run.id(),
                run == null ? "" : run.missionId(),
                run == null ? null : run.parentAgentRunId(),
                run == null ? "" : run.personaId(),
                run == null ? "" : run.routineId(),
                run == null ? null : run.subroutineId(),
                status);
    }

    public record MissionAdmissionPlan(
            Mission mission,
            List<PreparedAgentRun> agentRuns
    ) {
        public MissionAdmissionPlan {
            if (mission == null) {
                throw new IllegalArgumentException("MissionAdmissionPlan missing mission");
            }
            agentRuns = agentRuns == null ? List.of() : List.copyOf(agentRuns);
        }
    }

    public record PreparedAgentRun(
            AgentRun run,
            AgentRunHandle handle,
            AgentRunScheduled event
    ) {
        public PreparedAgentRun {
            if (run == null) {
                throw new IllegalArgumentException("PreparedAgentRun missing run");
            }
            if (handle == null) {
                throw new IllegalArgumentException("PreparedAgentRun missing handle");
            }
            if (event == null) {
                throw new IllegalArgumentException("PreparedAgentRun missing event");
            }
        }
    }
}
