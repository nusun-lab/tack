package us.nusun.tack.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.tack.example.tool.EngineeringWorkToolProfile;
import us.nusun.tack.example.tool.IntegrationToolProfile;
import us.nusun.tack.example.tool.SourceControlWriteToolProfile;
import us.nusun.tack.example.tool.TeamChatToolProfile;
import us.nusun.tack.framework.routine.RoutineProvider;
import us.nusun.tack.framework.inference.LlmPassType;
import us.nusun.tack.framework.routine.RoutineDefinition;
import us.nusun.tack.framework.routine.TackHeaders;

import java.util.List;

@ApplicationScoped
public class ApplicationDeveloperRoutine extends RouteBuilder implements RoutineProvider {
    public static final String ID = "software-team/application-developer";

    @Override
    public List<RoutineDefinition> routines() { return List.of(routine()); }

    public RoutineDefinition routine() {
        return RoutineDefinition.builder(ID)
                .name("Application Developer")
                .main("Deliver assigned implementation work through completion.", "direct:example-software-team-application-developer")
                .subroutine("work-selection", "Select the next assigned implementation task.", "direct:example-software-team-application-developer-work-selection")
                .subroutine("implementation", "Implement and validate one assigned task.", "direct:example-software-team-application-developer-implementation")
                .defaultLane("high-pass")
                .withLaneBinding(LlmPassType.SYNTHESIS, "low-pass")
                .withLaneBinding(LlmPassType.CONSOLIDATION, "low-pass")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-software-team-application-developer")
                .routeId("example-software-team-application-developer")
                .to("direct:dev-application-developer-root-enter")
                .setProperty("decision.application-developer-mission-complete.result", constant("no"))
                .loopDoWhile(exchangeProperty("decision.application-developer-mission-complete.result").isNotEqualTo("yes"))
                    .to("direct:dev-orientation")
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Select a focus based on the observed queue.
                            - reconcile: an assigned Story has merged and still needs Kaneo closure.
                            - implementation: an assigned to-do Story or Defect is executable, or assigned work has a
                              concrete next coding or test step. A PR waiting for merge leaves room for independent work.
                            - integration: help with an explicitly handed-off PR or when Architect is unavailable.
                            - coordination: a fresh technical question or a blocker on assigned work needs a response.
                            - complete: required Epics are accepted and done and delivery follow-up is settled.
                            - wait: the assigned queue is empty, or its work awaits new evidence.
                            """))
                    .to("tack:decide?id=dev-focus&choices=reconcile,implementation,integration,coordination,complete,wait")
                    .toD("direct:dev-focus-${exchangeProperty.decision.dev-focus.result}")
                .end()
                .end()
                .to("tack:thought-exit?name=application-developer-routine");

        from("direct:dev-application-developer-root-enter")
                .routeId("dev-application-developer-root-enter")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Mission anchor, exact Kaneo project/workspace/user IDs, selected task/Epic and repo owner/name,
                        branch/PR, actual validation and next action. Keep verified locators across queue checks.
                        """))
                .to("tack:thought-enter?name=application-developer-routine&maxTokens=500");

        from("direct:dev-orientation")
                .routeId("dev-orientation")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Reuse the verified mission project and whoami identity. Find assigned to-do and in-progress
                        Stories or Defects with native filters. Identify actionable work, open PR waits, merged Stories
                        to reconcile and merged Defects awaiting QA. Read related details only for the best candidate.
                        """))
                .to("tack:act?id=dev-orientation&toolProfile=" + EngineeringWorkToolProfile.ID);

        from("direct:dev-focus-implementation")
                .routeId("dev-focus-implementation")
                .to("direct:dev-work-selection")
                .choice()
                    .when(exchangeProperty("decision.developer-has-work.result").isEqualTo("yes"))
                        .to("direct:dev-implementation-step")
                    .otherwise()
                        .delay(30000)
                .end();

        from("direct:example-software-team-application-developer-work-selection")
                .routeId("example-software-team-application-developer-work-selection")
                .to("direct:dev-application-developer-root-enter")
                .to("direct:dev-work-selection")
                .to("tack:thought-exit?name=application-developer-routine");

        from("direct:example-software-team-application-developer-implementation")
                .routeId("example-software-team-application-developer-implementation")
                .to("direct:dev-application-developer-root-enter")
                .to("direct:dev-focus-implementation")
                .to("tack:thought-exit?name=application-developer-routine");

        from("direct:dev-work-selection")
                .routeId("dev-work-selection")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Use the Kaneo project and your exact user ID to confirm the best known candidate or find
                        one assigned to you. Verify Story or Defect classification, Epic relation, repository,
                        useful acceptance slice and done prerequisites. The parent Epic may be to-do or in-progress.
                        Claim an eligible assigned to-do task with update_task_status. Report its exact task, Epic
                        and repository locators. Skip PRs waiting only for integration or QA. Consider independent work.
                        """))
                .to("tack:act?id=dev-work-selection&toolProfile=" + EngineeringWorkToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Is one assigned Story or Defect selected in-progress with an executable next step?
                        Use the selected task's verified context. Answer yes when the ordinary source workflow can start.
                        """))
                .to("tack:decide?id=developer-has-work");

        from("direct:dev-implementation-step")
                .routeId("dev-implementation-step")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Deliver one coherent patch for the selected task in its existing repository. Inspect only the
                        source needed for this slice. Reuse the task branch and PR. Build and test locally from source
                        where possible, commit, push and open or update the PR. Link the Kaneo ticket and record actual
                        validation and its limits in the PR. A shared deployed application environment is not needed
                        for implementation or locally testable behavior. Attempt ordinary git operations before treating
                        access as blocked. For a task explicitly requesting investigation, return the requested technical
                        evidence without manufacturing a code change. Report exact branch, head and PR locators.
                        """))
                .to("tack:act?id=dev-delivery-step&toolProfile=" + SourceControlWriteToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Record new branch/PR, validation or a concrete blocker on the selected Kaneo task once.
                        An open PR leaves the task in-progress. A merged Story can be reconciled to done. QA retests
                        merged Defect fixes. For an investigation, record the findings and what PM can act on.
                        """))
                .to("tack:act?id=dev-task-evidence-update&toolProfile=" + EngineeringWorkToolProfile.ID);

        from("direct:dev-focus-reconcile")
                .routeId("dev-focus-reconcile")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Verify the assigned Story and its linked PR merged to the intended base. Record the merge and
                        move the Story to done. Repaired Defects remain in-progress for QA retest and closure.
                        """))
                .to("tack:act?id=dev-story-reconcile&toolProfile=" + EngineeringWorkToolProfile.ID);

        from("direct:dev-focus-integration")
                .routeId("dev-focus-integration")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Integrate one current-mission PR after Architect hands it off or is unavailable. Verify scope,
                        intended base, current mergeability, available validation and any concrete concerns. Gitea is
                        authoritative. Leave a concrete blocker for Architect if it cannot be merged safely.
                        """))
                .to("tack:act?id=dev-architect-pr-integration&toolProfile=" + IntegrationToolProfile.ID);

        from("direct:dev-focus-coordination")
                .routeId("dev-focus-coordination")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Answer one fresh technical question or tell the owner about a verified blocker they can resolve.
                        Link the authoritative task or PR. The Kaneo assignment and the Gitea PR are the handoffs.
                        """))
                .to("tack:act?id=dev-team-chat-sync&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:dev-focus-complete")
                .routeId("dev-focus-complete")
                .setProperty("decision.application-developer-mission-complete.result", constant("yes"));

        from("direct:dev-focus-wait")
                .routeId("dev-focus-wait")
                .delay(120000);
    }
}
