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
            "Drive implementation, review and update Plane around dev work, expose technical blockers, use PR-based delivery in the shared org, and turn goals into deliverable software.",
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
                .setProperty("projectBasics", constant("Use #forum-agents until exact channel, Plane project, and Gitea org are confirmed."))
                .setProperty("agentScratchpad", constant("No DEV notes yet."))
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
                        .setProperty("currentStep", constant("gitea-repo-check"))
                        .setProperty("currentRoutine", constant("Check the target Gitea repository for the current implementation path."))
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
                ;
    }
}
