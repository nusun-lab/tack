package us.nusun.llmshare.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.bean.LogTokens;
import us.nusun.llmshare.model.LlmJobRequest;
import us.nusun.llmshare.team.TeamRoleDefinition;
import us.nusun.llmshare.team.TeamRoleRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class Director extends RouteBuilder {
    public static final class DirectorAssignmentException extends RuntimeException {
        public DirectorAssignmentException(String message) {
            super(message);
        }

        public DirectorAssignmentException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @Inject
    LogTokens logTokens;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    TeamRoleRegistry teamRoleRegistry;

    @Override
    public void configure() {
        onException(DirectorAssignmentException.class)
                .handled(false)
                .logStackTrace(false)
                .logExhausted(false)
                .log(LoggingLevel.WARN, "Director assignment parsing failed: ${exception.message}");

        from("seda:software-team-director-start")
                .routeId("software-team-director-start")
                .to("direct:software-team-director");

        from("direct:software-team-director")
                .routeId("software-team-director")
                .setHeader("agent", constant("software-team/director"))
                .setHeader("role", constant("director"))
                .setHeader("agentLabel", constant("DIR"))
                .setHeader("systemPrompt", constant("{{agents.software-team.director.system-prompt}}"))
                .to("direct:prepare-director-request")
                .setHeader("responseSchema", constant("classpath:schemas/director-objectives-schema.json"))
                .process(exchange -> exchange.getIn().setBody("""
                        Original request: %s
                        Known team members:
                        %s
                        Available team roles:
                        %s
                        Build each mission with awareness of the real team above.
                        Every mission must explicitly mention the most relevant teammate, team role, or collaborator when coordination matters.
                        Do not write isolated missions that ignore the existence of the rest of the team.
                        For PM missions, explicitly mention the team and David Gordon (@aphelio) when collaboration setup or coordination is involved.
                        For PO and DEV missions, explicitly mention the PM when project setup or shared execution context matters.
                        If a mission depends on collaboration setup, assume David Gordon (@aphelio) must be included.
                        Return one JSON object where each key is an agent role id from the list above and each value is one concise mission statement for that role.
                        Include only the roles that are actually useful for this request.
                        Return JSON only.
                        """.formatted(
                        exchange.getProperty("requestPrompt", String.class),
                        teamRoleRegistry.directorTeamRoster(),
                        teamRoleRegistry.directorRoleGuide()
                )))
                .to("direct:log-director-banner")
                .setProperty("llmJobId", exchangeProperty("requestId"))
                .to("direct:ask-llm")
                .to("direct:parse-director-assignments")
                .wireTap("direct:dispatch-director-assignments")
                    .copy(true)
                .end()
                .removeProperty("llmJobId");

        from("direct:prepare-director-request")
                .routeId("prepare-director-request")
                .choice()
                    .when(body().isInstanceOf(LlmJobRequest.class))
                        .setProperty("requestPrompt", simple("${body.prompt}"))
                        .setProperty("bodyRequestId", simple("${body.jobId}"))
                    .otherwise()
                        .setProperty("requestPrompt", body())
                .end()
                .choice()
                    .when(header("jobId").isNotNull())
                        .setProperty("requestId", header("jobId"))
                    .when(exchangeProperty("bodyRequestId").isNotNull())
                        .setProperty("requestId", exchangeProperty("bodyRequestId"))
                    .otherwise()
                        .process(exchange -> exchange.setProperty("requestId", UUID.randomUUID()))
                .end()
                .validate(simple("${exchangeProperty.requestPrompt} != null && ${exchangeProperty.requestPrompt} regex '(?s).*\\S.*'"));

        from("direct:log-director-banner")
                .routeId("log-director-banner")
                .process(exchange -> logTokens.print(logTokens.wrap("DIR", "Director reporting for duty. Missions are being assigned.")));

        from("direct:dispatch-director-assignments")
                .routeId("dispatch-director-assignments")
                .removeProperty("llmJobId")
                .setBody(exchangeProperty("directorAssignments"))
                .split(body()).parallelProcessing()
                    .process(exchange -> {
                        @SuppressWarnings("unchecked")
                        Map.Entry<String, String> assignment = exchange.getIn().getBody(Map.Entry.class);
                        TeamRoleDefinition definition = teamRoleRegistry.getRequired(assignment.getKey());
                        exchange.setProperty("objective", assignment.getValue());
                        exchange.setProperty("roleDefinition", definition);
                    })
                    .toD("${exchangeProperty.roleDefinition.routeUri}")
                .end();

        from("direct:parse-director-assignments")
                .routeId("parse-director-assignments")
                .setBody(simple("${body.content}"))
                .process(exchange -> {
                    String rawJson = exchange.getIn().getBody(String.class);
                    if (rawJson == null || rawJson.isBlank()) {
                        throw new DirectorAssignmentException("Director LLM response content is empty");
                    }
                })
                .process(exchange -> {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, String> assignments = objectMapper.readValue(
                                exchange.getIn().getBody(String.class),
                                Map.class);
                        teamRoleRegistry.validateAssignments(assignments);
                        exchange.getIn().setBody(assignments);
                    } catch (Exception failure) {
                        throw new DirectorAssignmentException("Director LLM response is not valid JSON", failure);
                    }
                })
                .process(exchange -> {
                    @SuppressWarnings("unchecked")
                    Map<String, String> assignments = exchange.getIn().getBody(Map.class);
                    exchange.setProperty("directorAssignments", new ArrayList<>(assignments.entrySet()));
                });
    }
}
