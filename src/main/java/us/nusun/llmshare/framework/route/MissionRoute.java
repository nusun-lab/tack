package us.nusun.llmshare.framework.route;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.framework.exchange.AgentRunExchangeScope;
import us.nusun.llmshare.framework.inventory.PersonaCatalog;
import us.nusun.llmshare.framework.inventory.RoutineCatalog;
import us.nusun.llmshare.framework.model.AgentRunHandle;
import us.nusun.llmshare.framework.model.AgentRunRequest;
import us.nusun.llmshare.framework.model.Mission;
import us.nusun.llmshare.framework.model.MissionRequest;
import us.nusun.llmshare.framework.projection.AgentRunProjectionManager;
import us.nusun.llmshare.framework.state.AgentRun;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class MissionRoute extends RouteBuilder {
    @Inject PersonaCatalog personas;
    @Inject RoutineCatalog routines;
    @Inject AgentRunProjectionManager projections;

    private final AggregationStrategy agentRunHandles = (oldExchange, newExchange) -> {
        Exchange result = oldExchange == null ? newExchange : oldExchange;
        @SuppressWarnings("unchecked")
        List<AgentRunHandle> handles = oldExchange == null ? new ArrayList<>() : result.getIn().getBody(List.class);
        AgentRunHandle handle = newExchange.getIn().getBody(AgentRunHandle.class);
        if (handle != null) {
            handles.add(handle);
        }
        result.getIn().setBody(handles);
        return result;
    };

    @Override
    public void configure() {
        errorHandler(noErrorHandler());

        from("direct:start-mission")
                .routeId("start-mission")
                .validate(body().isInstanceOf(MissionRequest.class))
                .setProperty("missionRequest", body())
                .validate(simple("${exchangeProperty.missionRequest.goal} != null && ${exchangeProperty.missionRequest.goal.trim()} != ''"))
                .validate(simple("${exchangeProperty.missionRequest.agentRuns} != null && ${exchangeProperty.missionRequest.agentRuns.size} > 0"))
                .setProperty("mission", method(this, "newMission(${exchangeProperty.missionRequest.goal}, ${header.requestId})"))
                .split(simple("${exchangeProperty.missionRequest.agentRuns}"), agentRunHandles)
                    .to("direct:schedule-agent-run")
                .end()
                .setBody(method(this, "acceptedMissionResponse(${exchangeProperty.mission}, ${body})"));

        from("direct:start-agent-run")
                .routeId("start-agent-run")
                .to("direct:schedule-agent-run");

        from("direct:schedule-agent-run")
                .routeId("schedule-agent-run")
                .doTry()
                    .setProperty("scheduleParentAgentRun", exchangeProperty("agentRun"))
                    .validate(exchangeProperty("mission").isNotNull())
                    .process(AgentRunExchangeScope::captureAndClear)
                    .setProperty("agentRunRequest", method(AgentRunRequest.class, "fromBody(${body})"))
                    .setProperty("persona", method(personas, "getRequired(${exchangeProperty.agentRunRequest.personaId})"))
                    .setProperty("routine", method(routines, "getRequired(${exchangeProperty.agentRunRequest.routineId})"))
                    .setProperty("agentRun", method(this, "newAgentRun(${exchangeProperty.agentRunRequest}, ${exchangeProperty.mission}, ${exchangeProperty.scheduleParentAgentRun})"))
                    .setProperty("agentRunRouteUri", simple("${exchangeProperty.routine.routeUri}"))
                    .setProperty("scheduledAgentRunHandle", method(this, "handle(${exchangeProperty.agentRun}, 'accepted')"))
                    .process(exchange -> projections.record(exchange, "AGENT_RUN_STARTED"))
                    .setBody(simple("${exchangeProperty.agentRunRequest.input}"))
                    .wireTap("seda:execute-agent-run")
                    .end()
                .doFinally()
                    .setProperty("scheduledAgentRunReturn", exchangeProperty("scheduledAgentRunHandle"))
                    .removeProperty("scheduleParentAgentRun")
                    .process(AgentRunExchangeScope::restore)
                    .choice()
                        .when(exchangeProperty("scheduledAgentRunReturn").isNotNull())
                            .setBody(exchangeProperty("scheduledAgentRunReturn"))
                    .end()
                    .removeProperty("scheduledAgentRunReturn")
                .end();

        from("seda:execute-agent-run?concurrentConsumers=1")
                .routeId("execute-agent-run")
                .doTry()
                    .toD("${exchangeProperty.agentRunRouteUri}")
                    .bean(this, "completeAgentRun")
                .doCatch(Exception.class)
                    .bean(this, "failAgentRun")
                .doFinally()
                    .process(AgentRunExchangeScope::restore)
                .end();
    }

    public Mission newMission(String goal, String requestedId) {
        return new Mission(
                requestedId == null || requestedId.isBlank() ? "mission-" + UUID.randomUUID() : requestedId,
                goal,
                List.of(),
                "active",
                Map.of(),
                Instant.now());
    }

    public Map<String, Object> acceptedMissionResponse(Mission mission, List<AgentRunHandle> handles) {
        return Map.of(
                "missionId", mission.id(),
                "status", "accepted",
                "agentRuns", handles == null ? List.of() : List.copyOf(handles));
    }

    public void completeAgentRun(Exchange exchange) {
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        if (run != null && "active".equals(run.status())) {
            run.status("complete");
            projections.record(exchange, "AGENT_RUN_COMPLETED");
        }
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
    }

    public AgentRun newAgentRun(AgentRunRequest request, Mission mission, AgentRun parentRun) {
        return new AgentRun(
                "run-" + UUID.randomUUID(),
                mission.id(),
                parentRun == null ? null : parentRun.id(),
                request.personaId(),
                request.routineId(),
                request.workingState());
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
}
