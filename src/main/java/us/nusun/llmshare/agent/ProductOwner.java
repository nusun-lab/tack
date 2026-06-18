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
            "Clarify scope, priorities, and Gherkin acceptance criteria so the team can execute cleanly and document decisions in markdown.",
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
                        .setProperty("currentStep", constant("chat-focus-post"))
                        .setProperty("currentRoutine", constant("Post the current product focus in the shared chat channel."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("collaboration-sync"))
                        .setProperty("currentRoutine", constant("Ask one focused product question or give one focused product clarification in the shared chat channel."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("plane-backlog-review"))
                        .setProperty("currentRoutine", constant("Review the current backlog item or current priority in Plane."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("backstage-context-check"))
                        .setProperty("currentRoutine", constant("Check service context in Backstage for the current product work."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("wiki-product-context-read"))
                        .setProperty("currentRoutine", constant("Read the most relevant current product note in the shared markdown wiki repo."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("scope-refinement"))
                        .setProperty("currentRoutine", constant("Refine one scope boundary for the current work."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("gherkin-update"))
                        .setProperty("currentRoutine", constant("Draft or revise one Gherkin acceptance criterion for the current work."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("plane-priority-update"))
                        .setProperty("currentRoutine", constant("Update one priority or one backlog field in Plane if a change is needed."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("wiki-decision-update"))
                        .setProperty("currentRoutine", constant("Write one small product decision note in the shared markdown wiki repo."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("constraint-reconciliation"))
                        .setProperty("currentRoutine", constant("Resolve one open product question against known service constraints."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("loop-closeout"))
                        .setProperty("currentRoutine", constant("Summarize one remaining product gap or one confirmed product outcome for this loop."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("wiki-closeout"))
                        .setProperty("currentRoutine", constant("Write one small unresolved product follow-up note in the shared markdown wiki repo if needed."))
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
