package us.nusun.llmshare.framework.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.framework.inventory.PersonaCatalog;
import us.nusun.llmshare.framework.inventory.RoutineCatalog;
import us.nusun.llmshare.framework.exchange.AgentRunExchangeScope;
import us.nusun.llmshare.framework.state.AgentRun;
import us.nusun.llmshare.framework.model.Mission;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.model.StartAgentRunCommand;
import us.nusun.llmshare.framework.model.StartAgentRunResult;
import us.nusun.llmshare.framework.projection.AgentRunProjectionManager;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class MissionRoute extends RouteBuilder {
    @Inject PersonaCatalog personas;
    @Inject RoutineCatalog routines;
    @Inject AgentRunProjectionManager projections;
    @Inject ObjectMapper objectMapper;

    @ConfigProperty(name = "agent.framework.initial-agent-run.persona-id", defaultValue = "")
    String initialAgentRunPersonaId;

    @ConfigProperty(name = "agent.framework.initial-agent-run.routine-id", defaultValue = "")
    String initialAgentRunRoutineId;

    @Override
    public void configure() {
        from("direct:start-mission")
                .routeId("start-mission")
                .setProperty("missionGoal", bodyAs(String.class))
                .setProperty("mission", method(this, "newMission(${exchangeProperty.missionGoal}, ${header.requestId})"))
                .setBody(method(this, "initialAgentRunCommand(${exchangeProperty.mission})"))
                .to("direct:start-agent-run")
                .setProperty("startAgentRunResult", body())
                .setBody(method(this, "acceptedMissionResponse(${exchangeProperty.mission}, ${exchangeProperty.startAgentRunResult})"));

        from("direct:start-agent-run")
                .routeId("start-agent-run")
                .doTry()
                    .bean(this, "prepareAgentRun")
                    .toD("${exchangeProperty.agentRunRouteUri}")
                    .bean(this, "completeAgentRun")
                .doCatch(Exception.class)
                    .bean(this, "failAgentRun")
                .doFinally()
                    .bean(this, "restoreAgentRunScope")
                .end();
    }

    public Mission newMission(String goal, String requestedId) {
        return new Mission(
                requestedId == null || requestedId.isBlank() ? "mission-" + UUID.randomUUID() : requestedId,
                goal,
                java.util.List.of(),
                "active",
                Map.of(),
                Instant.now());
    }

    public StartAgentRunCommand initialAgentRunCommand(Mission mission) {
        return new StartAgentRunCommand(
                mission.id(),
                requiredInitialAgentRun(initialAgentRunPersonaId, "agent.framework.initial-agent-run.persona-id"),
                requiredInitialAgentRun(initialAgentRunRoutineId, "agent.framework.initial-agent-run.routine-id"),
                null,
                "Initial mission run",
                Map.of("goal", mission.goal()));
    }

    public Map<String, Object> acceptedMissionResponse(Mission mission, StartAgentRunResult result) {
        return Map.of(
                "missionId", mission.id(),
                "status", "accepted",
                "projection", result == null ? "" : result.agentRunId());
    }

    public void prepareAgentRun(Exchange exchange) {
        AgentRun parentRun = exchange.getProperty("agentRun", AgentRun.class);
        Mission exchangeMission = exchange.getProperty("mission", Mission.class);
        String defaultMissionId = exchangeMission == null ? null : exchangeMission.id();
        String defaultParentRunId = parentRun == null ? null : parentRun.id();
        AgentRunExchangeScope.captureAndClear(exchange);
        StartAgentRunCommand command = startAgentRunCommand(exchange.getIn().getBody(), defaultMissionId, defaultParentRunId);
        Mission mission = missionForCommand(command, exchangeMission);
        RoutineDefinition routine = requiredRoutine(command.routineId());
        AgentRun run = newAgentRun(command, mission);
        exchange.setProperty("mission", mission);
        exchange.setProperty("agentRun", run);
        exchange.setProperty("createdAgentRun", run);
        exchange.setProperty("agentRunRouteUri", routine.routeUri());
        exchange.setProperty("childAgentRunSetupComplete", true);
        projections.record(exchange, "AGENT_RUN_STARTED");
        exchange.getIn().setBody(command.input().isEmpty() ? null : command.input());
    }

    public void completeAgentRun(Exchange exchange) {
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        if (run != null && "active".equals(run.status())) {
            run.status("complete");
            projections.record(exchange, "AGENT_RUN_COMPLETED");
        }
        exchange.setProperty("childAgentRunResponse", result(run));
    }

    public void failAgentRun(Exchange exchange) {
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        Exception failure = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
        if (run != null) {
            run.status("failed");
            if (failure != null && failure.getMessage() != null && !failure.getMessage().isBlank()) {
                exchange.setProperty("latestPrimitiveFailure", failure.getMessage());
            }
            projections.record(exchange, "AGENT_RUN_FAILED");
        }
        exchange.setProperty("childAgentRunFailure", failure);
        if (!Boolean.TRUE.equals(exchange.getProperty("childAgentRunSetupComplete", Boolean.class))) {
            exchange.setProperty("childAgentRunSetupFailure", failure);
        }
        exchange.setProperty("childAgentRunResponse", result(run));
    }

    public void restoreAgentRunScope(Exchange exchange) throws Exception {
        StartAgentRunResult response = exchange.getProperty("childAgentRunResponse", StartAgentRunResult.class);
        Exception setupFailure = exchange.getProperty("childAgentRunSetupFailure", Exception.class);
        exchange.removeProperty("childAgentRunSetupComplete");
        exchange.removeProperty("childAgentRunResponse");
        exchange.removeProperty("childAgentRunFailure");
        exchange.removeProperty("childAgentRunSetupFailure");
        AgentRunExchangeScope.restore(exchange);
        exchange.getIn().setBody(response == null ? unknownAgentRunResult() : response);
        if (setupFailure != null) {
            throw setupFailure;
        }
    }

    public StartAgentRunCommand startAgentRunCommand(Object body, String defaultMissionId, String defaultParentRunId) {
        return StartAgentRunCommand.fromBody(body, objectMapper)
                .withDefaults(defaultMissionId, defaultParentRunId);
    }

    public Mission missionForCommand(StartAgentRunCommand command, Mission exchangeMission) {
        Mission mission = resolveMission(command, exchangeMission);
        personas.getRequired(command.personaId());
        return mission;
    }

    public RoutineDefinition requiredRoutine(String routineId) {
        return routines.getRequired(routineId);
    }

    public AgentRun newAgentRun(StartAgentRunCommand command, Mission mission) {
        return new AgentRun(
                "run-" + UUID.randomUUID(),
                mission.id(),
                command.parentAgentRunId(),
                command.personaId(),
                command.routineId(),
                command.workingState());
    }

    public StartAgentRunResult unknownAgentRunResult() {
        return new StartAgentRunResult("", "", null, "unknown");
    }

    private static Mission resolveMission(StartAgentRunCommand command, Mission exchangeMission) {
        if (command.missionId() == null || command.missionId().isBlank()) {
            throw new IllegalArgumentException("AgentRun start command missing missionId");
        }
        if (exchangeMission != null) {
            if (!command.missionId().equals(exchangeMission.id())) {
                throw new IllegalArgumentException("AgentRun start command missionId does not match exchange mission");
            }
            return exchangeMission;
        }
        return new Mission(command.missionId(), "", java.util.List.of(), "active", Map.of(), Instant.now());
    }

    public static StartAgentRunResult result(AgentRun run) {
        return new StartAgentRunResult(
                run == null ? "" : run.id(),
                run == null ? "" : run.missionId(),
                run == null ? null : run.parentAgentRunId(),
                run == null ? "unknown" : run.status());
    }

    private static String requiredInitialAgentRun(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " is required to start a mission");
        }
        return value;
    }
}
