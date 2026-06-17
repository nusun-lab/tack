package us.nusun.llmshare.agent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.team.TeamRoleDefinition;
import us.nusun.llmshare.team.TeamRoleRegistry;

@ApplicationScoped
public class ApplicationDeveloper extends RouteBuilder {
    private static final TeamRoleDefinition ROLE = new TeamRoleDefinition(
            "software-team/application-developer",
            "Application Developer",
            "direct:software-team-application-developer",
            "DEV",
            "Application Developer reporting for duty. Build intent is clear.",
            "Application Developer standing down for now. Sleeping until the next delivery push.",
            "Drive implementation, expose technical blockers, and turn goals into deliverable software.",
            "{{agents.software-team.application-developer.system-prompt}}"
    );

    @Inject
    TeamRoleRegistry teamRoleRegistry;

    @Override
    public void configure() {
        teamRoleRegistry.register(ROLE);

        from("direct:software-team-application-developer")
                .routeId("software-team-application-developer")
                .setHeader("agent", constant(ROLE.roleId()))
                .setHeader("role", constant("application-developer"))
                .setHeader("agentLabel", constant(ROLE.agentLabel()))
                .setHeader("wakeupMessage", constant(ROLE.wakeupMessage()))
                .setHeader("sleepMessage", constant(ROLE.sleepMessage()))
                .setHeader("systemPrompt", constant("{{agents.software-team.application-developer.system-prompt}}"))
                .setProperty("priorSummary", constant("No prior Application Developer summary yet."))
                .setProperty("dayCount", constant(0))
                .loopDoWhile(simple("${exchangeProperty.dayCount} < {{agents.software-team.max-days}}"))
                    .setProperty("dayCount", simple("${exchangeProperty.dayCount} + 1"))
                    .setProperty("cycleCount", constant(0))
                    .setProperty("done", constant(false))
                    .to("direct:log-agent-wakeup")
                    .loopDoWhile(simple("${exchangeProperty.done} != true && ${exchangeProperty.cycleCount} < {{agents.software-team.max-cycles}}"))
                        .to("direct:software-team-next-cycle")
                        .setProperty("dayRoutineResults", constant(""))
                        .setProperty("currentStep", constant("implementation-intake"))
                        .setProperty("currentRoutine", constant("Try to pull assigned work from Plane and inspect implementation context in Backstage before writing code."))
                        .setProperty("currentCollaboration", constant("Ask the Product Owner for clarity and keep the Project Manager informed in Mattermost."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("delivery-start"))
                        .setProperty("currentRoutine", constant("Attempt to start delivery work and check what should exist in Gitea for the implementation path."))
                        .setProperty("currentCollaboration", constant("Send implementation questions to the Product Owner and status to the Project Manager."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("pr-readiness"))
                        .setProperty("currentRoutine", constant("Try to validate what would go into a PR and whether ownership and acceptance criteria are stable."))
                        .setProperty("currentCollaboration", constant("Coordinate a handoff with both teammates and surface blockers."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("delivery-wrap-up"))
                        .setProperty("currentRoutine", constant("Close the day with a delivery report, explicit blockers, and what could not ship."))
                        .setProperty("currentCollaboration", constant("Return the engineering outcome to the Project Manager and Product Owner."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .to("direct:software-team-check-done")
                    .end()
                    .choice()
                        .when(simple("${exchangeProperty.dayCount} < {{agents.software-team.max-days}}"))
                            .to("direct:log-agent-sleep")
                            .delay(simple("{{agents.software-team.sleep-ms}}"))
                    .end()
                .end()
                ;
    }
}
