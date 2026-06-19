package us.nusun.llmshare.agent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.bean.AgentActivityTracker;
import us.nusun.llmshare.team.AgentPersonaDefinition;
import us.nusun.llmshare.team.AgentPersonaProvider;
import java.util.Map;

@ApplicationScoped
public class ProjectManager extends RouteBuilder implements AgentPersonaProvider {
    @Inject
    AgentActivityTracker agentActivityTracker;

    private static final AgentPersonaDefinition PERSONA = new AgentPersonaDefinition(
            "software-team/project-manager",
            "project-manager",
            "Project Manager",
            "direct:software-team-project-manager",
            "software-team-project-manager",
            "PM",
            "38;2;255;179;71",
            "Project Manager reporting for duty. Schedule pressure acknowledged.",
            "Project Manager standing down for now. Sleeping until the next planning window.",
            "Coordinate the work, break PO-driven work into discrete Plane tasks, manage risks, and keep the team aligned in shared channels.",
            """
            You are the Project Manager in a small software team.
            Coordinate sequencing, risks, and handoffs for the user's requested product.
            You own the shared project basics record for the team. Keep it current whenever collaboration setup becomes clearer or changes.
            Your first priorities are strict and ordered:
            first create or confirm a private Mattermost project channel and invite the full team;
            second create or confirm a private Plane project and invite the full team;
            third create or confirm the shared markdown wiki repo inside the existing Gitea org agent-workspace;
            fourth define or confirm the target application code repo name and location the developer should use.
            Use available MCP tools whenever they are relevant, prefer real tool-backed status over assumptions, and never claim external state you did not verify with a tool call.
            Keep responses concise.
            Return valid JSON only when asked for structured output.
            Do not include markdown fences, comments, prefixes, or explanatory text.
            """,
            Map.of(
                    "mattermost", "@project-manager-agent",
                    "gitea", "project-manager-agent",
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
        from("direct:software-team-project-manager")
                .routeId(PERSONA.routeId())
                .setHeader("agent", constant(PERSONA.roleId()))
                .setHeader("role", constant("project-manager"))
                .setHeader("agentLabel", constant(PERSONA.agentLabel()))
                .setHeader("wakeupMessage", constant(PERSONA.wakeupMessage()))
                .setHeader("sleepMessage", constant(PERSONA.sleepMessage()))
                .setHeader("systemPrompt", constant(PERSONA.systemPrompt()))
                .setProperty("priorSummary", constant("No prior Project Manager summary yet."))
                .setProperty("projectBasics", constant("Use #forum-agents until exact channel, Plane project, the agent-workspace wiki repo, and the target application code repo are confirmed."))
                .setProperty("agentScratchpad", constant("No PM notes yet."))
                .setProperty("collaborationSetupComplete", constant(false))
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
                        .to("direct:software-team-check-collaboration-setup")
                        .choice()
                            .when(simple("${exchangeProperty.collaborationSetupComplete} != true"))
                                .setProperty("bootstrapAttemptCount", constant(0))
                                .loopDoWhile(simple("${exchangeProperty.collaborationSetupComplete} != true && ${exchangeProperty.bootstrapAttemptCount} < {{agents.software-team.max-cycles}}"))
                                    .process(exchange -> {
                                        Integer bootstrapAttemptCount = exchange.getProperty("bootstrapAttemptCount", Integer.class);
                                        exchange.setProperty("bootstrapAttemptCount", bootstrapAttemptCount == null ? 1 : bootstrapAttemptCount + 1);
                                    })
                                    .setProperty("currentStep", constant("forum-fallback-check"))
                                    .setProperty("currentRoutine", constant("If the project collaboration location is unknown, use Mattermost channel #forum-agents for coordination."))
                                    .to("direct:log-software-team-step")
                                    .to("direct:software-team-routine-step")
                                    .setProperty("currentStep", constant("chat-channel-setup"))
                                    .setProperty("currentRoutine", constant("Create or confirm the private Mattermost project channel and invite the full team including David Gordon (@aphelio). Never create a duplicate."))
                                    .to("direct:log-software-team-step")
                                    .to("direct:software-team-routine-step")
                                    .setProperty("currentStep", constant("plane-project-setup"))
                                    .setProperty("currentRoutine", constant("Create or confirm the private Plane project and invite the full team including David Gordon (@aphelio). Never create a duplicate."))
                                    .to("direct:log-software-team-step")
                                    .to("direct:software-team-routine-step")
                                    .setProperty("currentStep", constant("gitea-wiki-repo-setup"))
                                    .setProperty("currentRoutine", constant("Create or confirm the shared markdown wiki repo inside the existing Gitea org agent-workspace. Never create a duplicate."))
                                    .to("direct:log-software-team-step")
                                    .to("direct:software-team-routine-step")
                                    .setProperty("currentStep", constant("code-repo-plan"))
                                    .setProperty("currentRoutine", constant("Define or confirm the target application code repo name and location the developer should use in Gitea."))
                                    .to("direct:log-software-team-step")
                                    .to("direct:software-team-routine-step")
                                    .setProperty("currentStep", constant("forum-announcement"))
                                    .setProperty("currentRoutine", constant("Post in #forum-agents the exact Mattermost channel, Plane project, agent-workspace wiki repo, and target application code repo for the project."))
                                    .to("direct:log-software-team-step")
                                    .to("direct:software-team-routine-step")
                                    .setProperty("currentStep", constant("collaboration-setup-gap"))
                                    .setProperty("currentRoutine", constant("Announce the current state of project setup in #forum-agents."))
                                    .to("direct:log-software-team-step")
                                    .to("direct:software-team-routine-step")
                                    .to("direct:software-team-check-collaboration-setup")
                                .end()
                        .end()
                        .setProperty("currentStep", constant("plane-item-review"))
                        .setProperty("currentRoutine", constant("Review one current PO-driven item in Plane."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("task-breakdown"))
                        .setProperty("currentRoutine", constant("Create or define one small discrete Plane task from the current PO-driven item."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("task-sequencing"))
                        .setProperty("currentRoutine", constant("Set or clarify the sequence of one Plane task."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("standup-status-check"))
                        .setProperty("currentRoutine", constant("Collect one focused work status update in the shared team channel."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("actionability-check"))
                        .setProperty("currentRoutine", constant("Check whether one planned task is actually actionable now."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("release-checkpoint"))
                        .setProperty("currentRoutine", constant("Assess one current delivery risk or one schedule risk."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("loop-closeout"))
                        .setProperty("currentRoutine", constant("Summarize one current blocker or one confirmed coordination outcome for this loop."))
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
