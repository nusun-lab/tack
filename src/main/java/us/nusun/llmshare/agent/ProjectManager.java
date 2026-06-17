package us.nusun.llmshare.agent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.team.TeamRoleDefinition;
import us.nusun.llmshare.team.TeamRoleRegistry;

@ApplicationScoped
public class ProjectManager extends RouteBuilder {
    private static final TeamRoleDefinition ROLE = new TeamRoleDefinition(
            "software-team/project-manager",
            "Project Manager",
            "direct:software-team-project-manager",
            "PM",
            "Project Manager reporting for duty. Schedule pressure acknowledged.",
            "Project Manager standing down for now. Sleeping until the next planning window.",
            "Coordinate the work, sequence delivery, manage risks, and keep the team aligned.",
            "{{agents.software-team.project-manager.system-prompt}}"
    );

    @Inject
    TeamRoleRegistry teamRoleRegistry;

    @Override
    public void configure() {
        teamRoleRegistry.register(ROLE);

        from("direct:software-team-project-manager")
                .routeId("software-team-project-manager")
                .setHeader("agent", constant(ROLE.roleId()))
                .setHeader("role", constant("project-manager"))
                .setHeader("agentLabel", constant(ROLE.agentLabel()))
                .setHeader("wakeupMessage", constant(ROLE.wakeupMessage()))
                .setHeader("sleepMessage", constant(ROLE.sleepMessage()))
                .setHeader("systemPrompt", constant("{{agents.software-team.project-manager.system-prompt}}"))
                .setProperty("priorSummary", constant("No prior Project Manager summary yet."))
                .setProperty("dayCount", constant(0))
                .loopDoWhile(simple("${exchangeProperty.dayCount} < {{agents.software-team.max-days}}"))
                    .setProperty("dayCount", simple("${exchangeProperty.dayCount} + 1"))
                    .setProperty("cycleCount", constant(0))
                    .setProperty("done", constant(false))
                    .to("direct:log-agent-wakeup")
                    .loopDoWhile(simple("${exchangeProperty.done} != true && ${exchangeProperty.cycleCount} < {{agents.software-team.max-cycles}}"))
                        .to("direct:software-team-next-cycle")
                        .setProperty("dayRoutineResults", constant(""))
                        .setProperty("currentStep", constant("day-kickoff"))
                        .setProperty("currentRoutine", constant("Try to open Plane for the sprint board, inspect Backstage for service ownership, and post a kickoff note in Mattermost."))
                        .setProperty("currentCollaboration", constant("Start the day with the Product Owner and Application Developer."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("standup-reconciliation"))
                        .setProperty("currentRoutine", constant("Run the standup, reconcile what is planned versus what is actually actionable, and chase status through Mattermost."))
                        .setProperty("currentCollaboration", constant("Ask the Product Owner for backlog clarity and the Application Developer for delivery status."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("release-checkpoint"))
                        .setProperty("currentRoutine", constant("Prepare a release checkpoint and assess schedule risk against what should exist in Backstage and Plane."))
                        .setProperty("currentCollaboration", constant("Push both teammates for missing updates and try to coordinate them in Mattermost."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("day-closeout"))
                        .setProperty("currentRoutine", constant("Close the day with a delivery summary, unresolved blockers, and the escalation posture for tomorrow."))
                        .setProperty("currentCollaboration", constant("Hand the final status back to the Product Owner and Application Developer."))
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
