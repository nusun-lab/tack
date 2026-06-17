package us.nusun.llmshare.agent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import us.nusun.llmshare.bean.LogTokens;

@ApplicationScoped
public class TeamPatterns extends RouteBuilder {
    @Inject
    LogTokens logTokens;

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
                .setHeader("responseSchema", constant("classpath:schemas/agent-routine-step-schema.json"))
                .setBody(simple("""
                        Objective: ${exchangeProperty.objective}
                        Original request: ${exchangeProperty.requestPrompt}
                        Day cycle: ${exchangeProperty.cycleCount}
                        Routine step: ${exchangeProperty.currentStep}
                        Day routine: ${exchangeProperty.currentRoutine}
                        Collaboration expectation: ${exchangeProperty.currentCollaboration}
                        Previous summary: ${exchangeProperty.priorSummary}
                        Available MCP services in this environment: Mattermost, Gitea, and Plane.
                        Backstage MCP is not available in this environment. If this routine mentions Backstage, say that it is unavailable and continue with the available MCP services instead of pretending you used it.
                        Before answering, use the available MCP tools relevant to this routine step.
                        You must make at least one concrete MCP tool call when a relevant tool exists.
                        Do not claim you opened, reviewed, updated, posted, checked, validated, or confirmed anything unless a real MCP tool call in this turn supports that statement.
                        If no relevant tool exists or the tool call fails, say so plainly in the summary and base the nextAction on that real limitation.
                        Keep the summary grounded in the actual MCP result, not a generic placeholder.
                        You must return exactly one JSON object that matches this shape:
                        - role: the current agent id
                        - summary: one concise sentence explaining the result of this routine step
                        - nextAction: one concise sentence describing the next move
                        Do not return status, error_code, error_type, message, next_action, markdown, or any wrapper object.
                        Return JSON only.
                        """))
                .to("direct:ask-llm")
                .setProperty("lastRoutineResult", simple("${body.content}"))
                .setBody(simple("${body.content}"))
                .unmarshal().json(JsonLibrary.Jackson)
                .setProperty("priorSummary", simple("${body[summary]}"))
                .choice()
                    .when(simple("${exchangeProperty.dayRoutineResults} == null || ${exchangeProperty.dayRoutineResults} == ''"))
                        .setProperty("dayRoutineResults", exchangeProperty("lastRoutineResult"))
                    .otherwise()
                        .setProperty("dayRoutineResults", simple("${exchangeProperty.dayRoutineResults}\n\n${exchangeProperty.lastRoutineResult}"))
                .end();

        from("direct:software-team-check-done")
                .routeId("software-team-check-done")
                .setHeader("responseSchema", constant("classpath:schemas/agent-done-check-schema.json"))
                .setBody(simple("""
                        Objective: ${exchangeProperty.objective}
                        Original request: ${exchangeProperty.requestPrompt}
                        Day cycle: ${exchangeProperty.cycleCount}
                        Full day routine JSON:
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
