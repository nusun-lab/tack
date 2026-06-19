package us.nusun.llmshare.agent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.bean.AgentActivityTracker;
import us.nusun.llmshare.team.AgentPersonaDefinition;
import us.nusun.llmshare.team.AgentPersonaProvider;
import java.util.Map;

@ApplicationScoped
public class ApplicationDeveloper extends RouteBuilder implements AgentPersonaProvider {
    @Inject
    AgentActivityTracker agentActivityTracker;

    private static final AgentPersonaDefinition PERSONA = new AgentPersonaDefinition(
            "software-team/application-developer",
            "application-developer",
            "Application Developer",
            "direct:software-team-application-developer",
            "software-team-application-developer",
            "DEV",
            "38;2;106;169;255",
            "Application Developer reporting for duty. Build intent is clear.",
            "Application Developer standing down for now. Sleeping until the next delivery push.",
            "Drive implementation, review and update Plane around dev work, expose technical blockers, use PR-based delivery in the shared org, and turn goals into deliverable software.",
            """
            You are the Application Developer in a small software team.
            Drive implementation and delivery details for the user's requested product.
            Use the exact project collaboration basics already established by the team, and do not invent new channels, projects, or orgs.
            As a strong default, do not start implementation work until the Project Manager has set up the project collaboration basics.
            Before coding or delivery work, prefer confirming that the PM has established the shared Mattermost channel, Plane project, wiki repo inside agent-workspace, and target application code repo.
            Do not assume the application code repo already exists. Use the repo named in project basics. If project basics do not name the code repo, treat that as a blocker and say so plainly.
            Use available MCP tools whenever they are relevant, prefer real tool-backed status over assumptions, and never claim external state you did not verify with a tool call.
            Keep responses concise.
            Return valid JSON only when asked for structured output.
            Do not include markdown fences, comments, prefixes, or explanatory text.
            """,
            Map.of(
                    "mattermost", "@application-developer-agent",
                    "gitea", "application-developer-agent",
                    "plane", "Dev"
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
        from("direct:software-team-application-developer")
                .routeId(PERSONA.routeId())
                .setHeader("agent", constant(PERSONA.roleId()))
                .setHeader("role", constant("application-developer"))
                .setHeader("agentLabel", constant(PERSONA.agentLabel()))
                .setHeader("wakeupMessage", constant(PERSONA.wakeupMessage()))
                .setHeader("sleepMessage", constant(PERSONA.sleepMessage()))
                .setHeader("systemPrompt", constant(PERSONA.systemPrompt()))
                .setProperty("priorSummary", constant("No prior Application Developer summary yet."))
                .setProperty("projectBasics", constant("Use #forum-agents until exact channel, Plane project, the agent-workspace wiki repo, and the target application code repo are confirmed."))
                .setProperty("agentScratchpad", constant("No DEV notes yet."))
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
                        .setProperty("currentStep", constant("chat-kickoff"))
                        .setProperty("currentRoutine", constant("Post the current implementation focus in the shared chat channel."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("collaboration-sync"))
                        .setProperty("currentRoutine", constant("Ask one focused implementation question or give one focused implementation status update in the shared chat channel."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("plane-assignment-review"))
                        .setProperty("currentRoutine", constant("Review one assigned task in Plane."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("plane-status-review"))
                        .setProperty("currentRoutine", constant("Check the current status field for one assigned task in Plane."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("backstage-context-check"))
                        .setProperty("currentRoutine", constant("Check implementation context in Backstage for the current task."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("wiki-engineering-context-read"))
                        .setProperty("currentRoutine", constant("Read the most relevant current engineering note in the shared markdown wiki repo."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("implementation-intake"))
                        .setProperty("currentRoutine", constant("Define one small implementation action for the current task."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("code-repo-readiness"))
                        .setProperty("currentRoutine", constant("Confirm the target application code repo from project basics. If it is named, create or confirm it in Gitea. If it is missing from project basics, report the blocker plainly."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("delivery-start"))
                        .setProperty("currentRoutine", constant("Start one small delivery action for the current task."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("pr-readiness"))
                        .setProperty("currentRoutine", constant("Check one PR readiness condition for the current task."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("plane-update"))
                        .setProperty("currentRoutine", constant("Update one Plane field for the current task if needed."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("plane-note-update"))
                        .setProperty("currentRoutine", constant("Write one small delivery note in Plane if needed."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("delivery-wrap-up"))
                        .setProperty("currentRoutine", constant("Summarize one current blocker or one confirmed delivery outcome for this loop."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("wiki-closeout"))
                        .setProperty("currentRoutine", constant("Write one small engineering note in the shared markdown wiki repo if needed."))
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
