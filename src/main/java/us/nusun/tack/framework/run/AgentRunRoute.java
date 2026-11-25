package us.nusun.tack.framework.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Body;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangeProperty;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.model.dataformat.JsonLibrary;
import us.nusun.tack.framework.routine.RoutineCatalog;
import us.nusun.tack.framework.routine.RoutineDefinition;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@ApplicationScoped
public class AgentRunRoute extends RouteBuilder {
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
                .setBody(exchange -> {
                    Object input = exchange.getMessage().getBody();
                    AgentRunRequest request = input instanceof AgentRunRequest typed
                            ? typed : mapper.convertValue(input, AgentRunRequest.class);
                    return List.of(request);
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
                .choice()
                    .when(method(agentRuns, "startIfEligible"))
                        .wireTap("direct:execute-agent-run")
                        .endChoice()
                    .otherwise()
                        .log(LoggingLevel.DEBUG, "Ignoring duplicate or ineligible AgentRunScheduled missionId=${body.missionId} agentRunId=${body.agentRunId}")
                .end();

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

    public void completeAgentRun(@ExchangeProperty("agentRun") AgentRun run, Exchange exchange) {
        if (run != null && !AgentRun.STATUS_COMPLETE.equals(run.status()) && !AgentRun.STATUS_FAILED.equals(run.status())) {
            run.status(AgentRun.STATUS_COMPLETE);
            projections.record(exchange, "AGENT_RUN_COMPLETED");
        }
    }

    public void failAgentRun(@ExchangeProperty("agentRun") AgentRun run, Exchange exchange) {
        Exception failure = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
        if (run != null) {
            run.status(AgentRun.STATUS_FAILED);
            if (failure != null && failure.getMessage() != null && !failure.getMessage().isBlank()) {
                exchange.setProperty("latestPrimitiveFailure", failure.getMessage());
            }
            projections.record(exchange, "AGENT_RUN_FAILED");
        }
    }

    public void restoreAgentRunContext(@Body AgentRunScheduled scheduled, Exchange exchange) {
        AgentRun run = agentRuns.requireRun(scheduled);
        exchange.setProperty("agentRun", run);
        exchange.setProperty("mission", agentRuns.requireMission(scheduled.missionId()));
        RoutineDefinition routine = routines.getRequired(run.routineId());
        exchange.setProperty("routine", routine);
        exchange.setProperty("agentRunRouteUri", routine.routeUri(run.subroutineId()));
        exchange.getMessage().setBody(Objects.requireNonNullElse(run.input(), Map.of()));
        projections.record(exchange, "AGENT_RUN_STARTED");
    }
}
