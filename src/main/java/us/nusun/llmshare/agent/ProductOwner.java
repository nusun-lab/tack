package us.nusun.llmshare.agent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.bean.AgentActivityTracker;
import us.nusun.llmshare.team.AgentPersonaDefinition;
import us.nusun.llmshare.team.AgentPersonaProvider;
import java.util.Map;

@ApplicationScoped
public class ProductOwner extends RouteBuilder implements AgentPersonaProvider {
    @Inject
    AgentActivityTracker agentActivityTracker;

    private static final AgentPersonaDefinition PERSONA = new AgentPersonaDefinition(
            "software-team/product-owner",
            "product-owner",
            "Product Owner",
            "direct:software-team-product-owner",
            "software-team-product-owner",
            "PO",
            "38;2;114;209;163",
            "Product Owner reporting for duty. Scope is under control until proven otherwise.",
            "Product Owner standing down for now. Sleeping until the next backlog pass.",
            "Clarify scope, priorities, and Gherkin acceptance criteria so the team can execute cleanly and document decisions in markdown.",
            """
            You are the Product Owner in a small software team.
            Clarify scope, backlog priorities, and acceptance criteria for the user's requested product.
            Use the exact project collaboration basics already established by the team, and do not invent new channels, projects, or orgs.
            As a strong default, do not start normal product execution work until the Project Manager has set up the project collaboration basics.
            Before backlog shaping or acceptance-detail work, prefer confirming that the PM has established the shared Mattermost channel, Plane project, and wiki repo inside agent-workspace.
            Use available MCP tools whenever they are relevant, prefer real tool-backed status over assumptions, and never claim external state you did not verify with a tool call.
            Keep responses concise.
            Return valid JSON only when asked for structured output.
            Do not include markdown fences, comments, prefixes, or explanatory text.
            """,
            Map.of(
                    "mattermost", "@product-owner-agent",
                    "gitea", "product-owner-agent",
                    "plane", "PO"
            ),
            true,
            true
    );

    @Override
    public AgentPersonaDefinition persona() {
        return PERSONA;
    }

    @Override
    public void configure() {
        from("direct:software-team-product-owner")
                .routeId(PERSONA.routeId())
                .setHeader("agent", constant(PERSONA.roleId()))
                .setHeader("role", constant("product-owner"))
                .setHeader("agentLabel", constant(PERSONA.agentLabel()))
                .setHeader("wakeupMessage", constant(PERSONA.wakeupMessage()))
                .setHeader("sleepMessage", constant(PERSONA.sleepMessage()))
                .setHeader("systemPrompt", constant(PERSONA.systemPrompt()))
                .setProperty("priorSummary", constant("No prior Product Owner summary yet."))
                .setProperty("projectBasics", constant("Use #forum-agents until exact channel, Plane project, and the agent-workspace wiki repo are confirmed."))
                .setProperty("agentScratchpad", constant("No PO notes yet."))
                .setProperty("dayCount", constant(0))
                .loopDoWhile(simple("${exchangeProperty.dayCount} < {{agents.software-team.max-days}}"))
                    .process(exchange -> {
                        Integer dayCount = exchange.getProperty("dayCount", Integer.class);
                        exchange.setProperty("dayCount", dayCount == null ? 1 : dayCount + 1);
                    })
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
                .process(agentActivityTracker::markRouteComplete)
                ;
    }
}
