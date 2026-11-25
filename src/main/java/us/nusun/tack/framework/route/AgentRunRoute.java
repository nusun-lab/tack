package us.nusun.tack.framework.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Body;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.model.dataformat.JsonLibrary;
import us.nusun.tack.framework.inventory.PersonaCatalog;
import us.nusun.tack.framework.inventory.RoutineCatalog;
import us.nusun.tack.framework.model.AgentRunHandle;
import us.nusun.tack.framework.model.AgentRunRequest;
import us.nusun.tack.framework.model.AgentRunScheduled;
import us.nusun.tack.framework.model.Mission;
import us.nusun.tack.framework.model.RoutineDefinition;
import us.nusun.tack.framework.projection.AgentRunProjectionManager;
import us.nusun.tack.framework.service.AgentRunAdmissionService;
import us.nusun.tack.framework.state.AgentRun;
import us.nusun.tack.framework.state.AgentRunStore;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class AgentRunRoute extends RouteBuilder {
    @Inject PersonaCatalog personas;
    @Inject RoutineCatalog routines;
    @Inject AgentRunProjectionManager projections;
    @Inject AgentRunStore agentRuns;
    @Inject AgentRunAdmissionService admissions;
    @Inject ObjectMapper mapper;

    @Override
    public void configure() {
        errorHandler(noErrorHandler());

        from("direct:start-agent-run")
                .routeId("start-agent-run")
                .process(exchange -> {
                    Object input = exchange.getMessage().getBody();
                    AgentRunRequest request = input instanceof AgentRunRequest typed
                            ? typed : mapper.convertValue(input, AgentRunRequest.class);
                    exchange.getMessage().setBody(List.of(request));
                })
                .to("direct:start-agent-runs")
                .setBody(simple("${body[0]}"));

        from("direct:start-agent-runs")
                .routeId("start-agent-runs")
                .validate(exchangeProperty("mission").isNotNull())
                .validate(body().isInstanceOf(List.class))
                .bean(admissions, "admitAll")
                .to("direct:publish-accepted-agent-runs");

        from("direct:publish-accepted-agent-runs")
                .routeId("publish-accepted-agent-runs")
                .split(body())
                    .setBody(method(this, "scheduledEvent"))
                    .setHeader(KafkaConstants.KEY, simple("${body.agentRunId}"))
                    .marshal().json(JsonLibrary.Jackson, AgentRunScheduled.class)
                    .to("kafka:{{agent-run.scheduled.topic:agent-runs.scheduled}}")
                .end();

        from("kafka:{{agent-run.scheduled.topic:agent-runs.scheduled}}"
                + "?groupId={{agent-run.scheduled.consumer.group:agent-run-scheduler}}"
                + "&autoOffsetReset=earliest"
                + "&maxPollRecords=1"
                + "&maxPollIntervalMs=900000")
                .routeId("agent-run-scheduled-consumer")
                .autoStartup("{{agent-run.scheduled.consumer.enabled:true}}")
                .unmarshal().json(JsonLibrary.Jackson, AgentRunScheduled.class)
                .to("direct:handle-agent-run-scheduled");

        from("direct:handle-agent-run-scheduled")
                .routeId("handle-agent-run-scheduled")
                .validate(body().isInstanceOf(AgentRunScheduled.class))
                .setProperty("agentRunStartAccepted", method(this, "startScheduledAgentRun"))
                .choice()
                    .when(exchangeProperty("agentRunStartAccepted").isEqualTo(true))
                        .wireTap("direct:execute-agent-run")
                        .endChoice()
                    .otherwise()
                        .log(LoggingLevel.DEBUG, "Ignoring duplicate or ineligible AgentRunScheduled missionId=${body.missionId} agentRunId=${body.agentRunId}")
                .end()
                .removeProperty("agentRunStartAccepted");

        from("direct:execute-agent-run")
                .routeId("execute-agent-run")
                .doTry()
                    .validate(body().isInstanceOf(AgentRunScheduled.class))
                    .bean(this, "restoreAgentRunContext")
                    .toD("${exchangeProperty.agentRunRouteUri}")
                    .bean(this, "completeAgentRun")
                .doCatch(Exception.class)
                    .bean(this, "failAgentRun")
                .end();
    }

    public AgentRunScheduled scheduledEvent(@Body AgentRunHandle handle) {
        return new AgentRunScheduled(handle.missionId(), handle.agentRunId());
    }

    public void completeAgentRun(Exchange exchange) {
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        if (run != null && !AgentRun.STATUS_COMPLETE.equals(run.status()) && !AgentRun.STATUS_FAILED.equals(run.status())) {
            run.status(AgentRun.STATUS_COMPLETE);
            projections.record(exchange, "AGENT_RUN_COMPLETED");
        }
    }

    public void failAgentRun(Exchange exchange) {
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        Exception failure = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
        if (run != null) {
            run.status(AgentRun.STATUS_FAILED);
            if (failure != null && failure.getMessage() != null && !failure.getMessage().isBlank()) {
                exchange.setProperty("latestPrimitiveFailure", failure.getMessage());
            }
            projections.record(exchange, "AGENT_RUN_FAILED");
        }
    }

    public boolean startScheduledAgentRun(@Body AgentRunScheduled scheduled, Exchange exchange) {
        if (scheduled == null || !agentRuns.startIfEligible(scheduled)) {
            return false;
        }
        restoreAgentRunContext(scheduled, exchange);
        projections.record(exchange, "AGENT_RUN_STARTED");
        return true;
    }

    public void restoreAgentRunContext(@Body AgentRunScheduled scheduled, Exchange exchange) {
        if (scheduled == null) {
            throw new IllegalArgumentException("AgentRunScheduled event is required");
        }
        Mission mission = agentRuns.requireMission(scheduled.missionId());
        AgentRun run = agentRuns.requireRun(scheduled);
        RoutineDefinition routine = routines.getRequired(run.routineId());
        exchange.setProperty("mission", mission);
        exchange.setProperty("agentRun", run);
        exchange.setProperty("persona", personas.getRequired(run.personaId()));
        exchange.setProperty("routine", routine);
        exchange.setProperty("subroutine", run.subroutineId() == null ? null : routine.requireSubroutine(run.subroutineId()));
        exchange.setProperty("agentRunRouteUri", routines.routeUriFor(run));
        exchange.getIn().setBody(initialRoutineBody(run));
    }

    private Object initialRoutineBody(AgentRun run) {
        return run.workingState().getOrDefault("input", Map.of());
    }
}
