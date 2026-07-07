package us.nusun.llmshare.framework.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Body;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangeProperty;
import org.apache.camel.Header;
import org.apache.camel.support.DefaultExchange;
import us.nusun.llmshare.framework.inventory.PersonaCatalog;
import us.nusun.llmshare.framework.inventory.RoutineCatalog;
import us.nusun.llmshare.framework.model.AgentRunAdmission;
import us.nusun.llmshare.framework.model.AgentRunHandle;
import us.nusun.llmshare.framework.model.AgentRunRequest;
import us.nusun.llmshare.framework.model.AgentRunScheduled;
import us.nusun.llmshare.framework.model.Mission;
import us.nusun.llmshare.framework.model.MissionRequest;
import us.nusun.llmshare.framework.projection.AgentRunProjectionManager;
import us.nusun.llmshare.framework.state.AgentRun;
import us.nusun.llmshare.framework.state.AgentRunStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class AgentRunAdmissionService {
    @Inject PersonaCatalog personas;
    @Inject RoutineCatalog routines;
    @Inject AgentRunProjectionManager projections;
    @Inject AgentRunStore agentRuns;

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
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("AgentRun admission requires at least one AgentRunRequest");
        }

        List<PreparedAgentRun> prepared = requests.stream()
                .map(request -> prepareAgentRun(mission, parent, request))
                .toList();
        return commitPrepared(exchange, mission, prepared);
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
                Map.of(),
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
        return commitPrepared(exchange, plan.mission(), plan.agentRuns());
    }

    private PreparedAgentRun prepareAgentRun(Mission mission, AgentRun parent, AgentRunRequest request) {
        if (mission == null) {
            throw new IllegalArgumentException("AgentRun preparation requires mission");
        }
        if (request == null) {
            throw new IllegalArgumentException("AgentRun request is required");
        }

        personas.getRequired(request.personaId());
        routines.getRequired(request.routineId());

        AgentRun run = new AgentRun(
                "run-" + UUID.randomUUID(),
                mission.id(),
                parent == null ? null : parent.id(),
                request.personaId(),
                request.routineId(),
                request.workingState());
        run.status(AgentRun.STATUS_ACCEPTED);

        return new PreparedAgentRun(
                run,
                handle(run, AgentRun.STATUS_ACCEPTED),
                new AgentRunScheduled(run.missionId(), run.id()));
    }

    private List<AgentRunAdmission> commitPrepared(Exchange exchange, Mission mission, List<PreparedAgentRun> preparedRuns) {
        agentRuns.putMission(mission);
        List<AgentRunAdmission> admissions = new ArrayList<>();
        for (PreparedAgentRun prepared : preparedRuns) {
            agentRuns.putRun(prepared.run());
            recordAccepted(exchange, mission, prepared.run());
            admissions.add(new AgentRunAdmission(prepared.handle(), prepared.event()));
        }
        return List.copyOf(admissions);
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

    private void recordAccepted(Exchange source, Mission mission, AgentRun run) {
        Exchange projectionExchange = new DefaultExchange(source.getContext());
        projectionExchange.setProperty("mission", mission);
        projectionExchange.setProperty("agentRun", run);
        projections.record(projectionExchange, "AGENT_RUN_ACCEPTED");
    }

    public static AgentRunHandle handle(AgentRun run, String status) {
        return new AgentRunHandle(
                run == null ? "" : run.id(),
                run == null ? "" : run.missionId(),
                run == null ? null : run.parentAgentRunId(),
                run == null ? "" : run.personaId(),
                run == null ? "" : run.routineId(),
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
