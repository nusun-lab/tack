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
            "Coordinate the work, break PO-driven work into discrete Plane tasks, manage risks, and keep the team aligned in shared channels.",
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
                        .setProperty("currentStep", constant("chat-kickoff"))
                        .setProperty("currentRoutine", constant("Post the current coordination focus in the shared chat channel."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("collaboration-sync"))
                        .setProperty("currentRoutine", constant("Ask for one focused status update or answer one focused coordination question in the shared chat channel."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("plane-project-check"))
                        .setProperty("currentRoutine", constant("Check that the relevant Plane project or board exists and is reachable."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("chat-channel-check"))
                        .setProperty("currentRoutine", constant("Check that the shared team chat channel exists and is usable."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("wiki-repo-check"))
                        .setProperty("currentRoutine", constant("Check that the shared markdown wiki repo exists and is reachable."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
                        .setProperty("currentStep", constant("backstage-context-check"))
                        .setProperty("currentRoutine", constant("Check service ownership context in Backstage for the current work."))
                        .to("direct:log-software-team-step")
                        .to("direct:software-team-routine-step")
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
                        .setProperty("currentRoutine", constant("Collect one focused work status update in the shared chat channel."))
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
                        .setProperty("currentStep", constant("wiki-closeout"))
                        .setProperty("currentRoutine", constant("Write one small coordination note in the shared markdown wiki repo if needed."))
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
