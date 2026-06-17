package us.nusun.llmshare.agent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.team.TeamRoleDefinition;
import us.nusun.llmshare.team.TeamRoleRegistry;

@ApplicationScoped
public class ProductOwner extends RouteBuilder {
    private static final TeamRoleDefinition ROLE = new TeamRoleDefinition(
            "software-team/product-owner",
            "Product Owner",
            "direct:software-team-product-owner",
            "PO",
            "Product Owner reporting for duty. Scope is under control until proven otherwise.",
            "Product Owner standing down for now. Sleeping until the next backlog pass.",
            "Clarify scope, priorities, and acceptance criteria so the team can execute cleanly.",
            "{{agents.software-team.product-owner.system-prompt}}"
    );

    @Inject
    TeamRoleRegistry teamRoleRegistry;

    @Override
    public void configure() {
        teamRoleRegistry.register(ROLE);

        from("direct:software-team-product-owner")
                .routeId("software-team-product-owner")
                .setHeader("agent", constant(ROLE.roleId()))
                .setHeader("role", constant("product-owner"))
                .setHeader("agentLabel", constant(ROLE.agentLabel()))
                .setHeader("wakeupMessage", constant(ROLE.wakeupMessage()))
                .setHeader("sleepMessage", constant(ROLE.sleepMessage()))
                .setHeader("systemPrompt", constant("{{agents.software-team.product-owner.system-prompt}}"))
                .setProperty("priorSummary", constant("No prior Product Owner summary yet."))
                .setProperty("dayCount", constant(0))
                .loopDoWhile(simple("${exchangeProperty.dayCount} < {{agents.software-team.max-days}}"))
                    .setProperty("dayCount", simple("${exchangeProperty.dayCount} + 1"))
                    .setProperty("cycleCount", constant(0))
                    .setProperty("done", constant(false))
                    .to("direct:log-agent-wakeup")
                    .loopDoWhile(simple("${exchangeProperty.done} != true && ${exchangeProperty.cycleCount} < {{agents.software-team.max-cycles}}"))
                        .to("direct:software-team-next-cycle")
                        .setProperty("dayRoutineResults", constant(""))
                        .setProperty("currentStep", constant("backlog-review"))
                        .setProperty("currentRoutine", constant("Try to review the backlog in Plane and validate service context in Backstage before shaping the day."))
                        .setProperty("currentCollaboration", constant("Align with the Project Manager and prepare useful guidance for the Application Developer."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("scope-refinement"))
                        .setProperty("currentRoutine", constant("Refine scope and acceptance criteria for the current work and try to update priorities in Plane."))
                        .setProperty("currentCollaboration", constant("Answer the Project Manager and provide actionable direction to the Application Developer in Mattermost."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("constraint-reconciliation"))
                        .setProperty("currentRoutine", constant("Reconcile open product questions with the service constraints that should be discoverable in Backstage."))
                        .setProperty("currentCollaboration", constant("Coordinate with the Project Manager on priorities and unblock the Application Developer."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("day-closeout"))
                        .setProperty("currentRoutine", constant("Close the day with backlog state, missing decisions, and what still lacks confirmation."))
                        .setProperty("currentCollaboration", constant("Report the product outcome to the Project Manager and set expectations for the Application Developer."))
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
