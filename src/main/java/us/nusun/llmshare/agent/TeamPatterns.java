package us.nusun.llmshare.agent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.ValidationException;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jsonvalidator.JsonValidationException;
import org.apache.camel.model.dataformat.JsonLibrary;
import us.nusun.llmshare.bean.LogTokens;
import us.nusun.llmshare.bean.RoutineStepSupport;
import us.nusun.llmshare.model.RoutineStepResult;

@ApplicationScoped
public class TeamPatterns extends RouteBuilder {
    @Inject
    LogTokens logTokens;

    @Inject
    RoutineStepSupport routineStepSupport;

    @Override
    public void configure() {
        from("direct:software-team-next-cycle")
                .routeId("software-team-next-cycle")
                .setProperty("cycleCount", simple("${exchangeProperty.cycleCount}++"));

        from("direct:log-software-team-step")
                .routeId("log-software-team-step")
                .process(exchange -> logTokens.print(logTokens.wrapDim(
                        exchange.getIn().getHeader("agentLabel", String.class),
                        "STEP " + exchange.getProperty("currentStep", String.class)
                                + "#" + exchange.getProperty("cycleCount", Integer.class))));

        from("direct:log-agent-wakeup")
                .routeId("log-agent-wakeup")
                .process(exchange -> logTokens.print(logTokens.wrap(
                        exchange.getIn().getHeader("agentLabel", String.class),
                        exchange.getIn().getHeader("wakeupMessage", String.class))));

        from("direct:log-agent-sleep")
                .routeId("log-agent-sleep")
                .process(exchange -> logTokens.print(logTokens.wrap(
                        exchange.getIn().getHeader("agentLabel", String.class),
                        exchange.getIn().getHeader("sleepMessage", String.class))));

        from("direct:software-team-routine-step")
                .routeId("software-team-routine-step")
                .process(routineStepSupport::prepareExploration)
                .to("direct:ask-llm")
                .process(routineStepSupport::captureExploration)
                .doTry()
                    .to("direct:software-team-routine-step-format")
                .doFinally()
                    .process(routineStepSupport::restoreRoleHeaders)
                .end();

        from("direct:software-team-routine-step-format")
                .routeId("software-team-routine-step-format")
                .process(routineStepSupport::prepareFormatter)
                .doTry()
                    .to("direct:software-team-routine-step-format-once")
                .doCatch(JsonValidationException.class, ValidationException.class)
                    .process(routineStepSupport::captureFormatterFailure)
                    .to("direct:software-team-routine-step-format-retry")
                .end();

        from("direct:software-team-routine-step-format-retry")
                .routeId("software-team-routine-step-format-retry")
                .process(routineStepSupport::prepareFormatterRetry)
                .doTry()
                    .to("direct:software-team-routine-step-format-once")
                .doCatch(JsonValidationException.class, ValidationException.class)
                    .process(routineStepSupport::captureFormatterFailure)
                    .process(routineStepSupport::failFormatter)
                .end();

        from("direct:software-team-routine-step-format-once")
                .routeId("software-team-routine-step-format-once")
                .to("direct:ask-llm")
                .process(routineStepSupport::captureFormatterResult)
                .to("json-validator:classpath:schemas/agent-routine-step-schema.json")
                .unmarshal().json(JsonLibrary.Jackson, RoutineStepResult.class)
                .process(routineStepSupport::applyRoutineStepResult);

        from("direct:software-team-check-done")
                .routeId("software-team-check-done")
                .setHeader("responseSchema", constant("classpath:schemas/agent-done-check-schema.json"))
                .setBody(simple("""
                        Objective: ${exchangeProperty.objective}
                        Original request: ${exchangeProperty.requestPrompt}
                        Cycle count: ${exchangeProperty.cycleCount}
                        Full routine result JSON:
                        ${exchangeProperty.dayRoutineResults}
                        Judge completion only from the actual routine results above.
                        Do not assume work happened unless the routine summaries say it happened.
                        Based on the objective, are you done?
                        You must return exactly one JSON object that matches this shape:
                        - done: true or false
                        - reason: one concise sentence
                        Do not return status, error_code, error_type, message, or any wrapper object.
                        Return JSON only.
                        """))
                .to("direct:ask-llm")
                .setBody(simple("${body.content}"))
                .unmarshal().json(JsonLibrary.Jackson)
                .setProperty("done", simple("${body[done]}"))
                .setProperty("doneReason", simple("${body[reason]}"));
    }
}
