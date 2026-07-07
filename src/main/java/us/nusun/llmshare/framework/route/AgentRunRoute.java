package us.nusun.llmshare.framework.route;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Body;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.model.dataformat.JsonLibrary;
import us.nusun.llmshare.framework.inventory.PersonaCatalog;
import us.nusun.llmshare.framework.inventory.RoutineCatalog;
import us.nusun.llmshare.framework.model.AgentRunRequest;
import us.nusun.llmshare.framework.model.AgentRunScheduled;
import us.nusun.llmshare.framework.model.Mission;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.projection.AgentRunProjectionManager;
import us.nusun.llmshare.framework.service.AgentRunAdmissionService;
import us.nusun.llmshare.framework.state.AgentRun;
import us.nusun.llmshare.framework.state.AgentRunStore;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class AgentRunRoute extends RouteBuilder {
    @Inject PersonaCatalog personas;
    @Inject RoutineCatalog routines;
    @Inject AgentRunProjectionManager projections;
    @Inject AgentRunStore agentRuns;
    @Inject AgentRunAdmissionService admissions;

    @Override
    public void configure() {
        errorHandler(noErrorHandler());

        from("direct:start-agent-run")
                .routeId("start-agent-run")
                .doTry()
                    .validate(exchangeProperty("mission").isNotNull())
                    .marshal().json(JsonLibrary.Jackson)
                    .unmarshal().json(JsonLibrary.Jackson, AgentRunRequest.class)
                    .bean(admissions, "admit")
                    .setProperty("agentRunAdmission", body())
                    .setBody(simple("${body.event}"))
                    .to("direct:publish-agent-run-scheduled")
                    .setBody(simple("${exchangeProperty.agentRunAdmission.handle}"))
                .doFinally()
                    .removeProperty("agentRunAdmission")
                .end();


        from("direct:start-agent-runs")
                .routeId("start-agent-runs")
                .validate(exchangeProperty("mission").isNotNull())
                .validate(body().isInstanceOf(List.class))
                .bean(admissions, "admitAll")
                .setProperty("agentRunAdmissions", body())
                .split(body())
                    .setBody(simple("${body.event}"))
                    .to("direct:publish-agent-run-scheduled")
                .end()
                .setBody(simple("${exchangeProperty.agentRunAdmissions}"))
                .setBody(method(admissions, "handles"))
                .removeProperty("agentRunAdmissions");

        from("direct:publish-agent-run-scheduled")
                .routeId("publish-agent-run-scheduled")
                .validate(body().isInstanceOf(AgentRunScheduled.class))
                .setHeader(KafkaConstants.KEY, simple("${body.agentRunId}"))
                .marshal().json(JsonLibrary.Jackson, AgentRunScheduled.class)
                .toD("kafka:{{agent-run.scheduled.topic:agent-runs.scheduled}}");

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
        exchange.setProperty("agentRunRouteUri", routine.routeUri());
        exchange.getIn().setBody(initialRoutineBody(run));
    }

    private Object initialRoutineBody(AgentRun run) {
        return run.workingState().getOrDefault("input", Map.of());
    }
}
