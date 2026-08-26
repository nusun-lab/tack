package us.nusun.llmshare.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.example.tool.ProjectManagementToolProfile;
import us.nusun.llmshare.example.tool.SourceControlToolProfile;
import us.nusun.llmshare.example.tool.SourceControlWriteToolProfile;
import us.nusun.llmshare.example.tool.TeamChatToolProfile;
import us.nusun.llmshare.example.tool.WorkspaceReadonlyToolProfile;
import us.nusun.llmshare.framework.inventory.RoutineProvider;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.tack.TackHeaders;

import java.util.List;

@ApplicationScoped
public class ApplicationDeveloperRoutine extends RouteBuilder implements RoutineProvider {
    public static final String ID = "software-team/application-developer";

    @Override
    public List<RoutineDefinition> routines() {
        return List.of(routine());
    }

    public RoutineDefinition routine() {
        return RoutineDefinition.builder(ID)
                .name("Application Developer")
                .main("""
                        Own application development for the whole mission through completion. Consume assigned implementation
                        children from Plane, continue active work before starting Todo work, make coherent source changes,
                        validate them, create or update reviewable pull requests, and leave completed implementation work
                        In Progress with REVIEW READY evidence for Project Manager review. Product parents are never developer tasks.
                        """, "direct:example-software-team-application-developer")
                .subroutine(
                        "work-selection",
                        "Find or start one assigned implementation child using Plane Todo/In Progress state without changing source files.",
                        "direct:example-software-team-application-developer-work-selection")
                .subroutine(
                        "implementation",
                        "Advance one assigned implementation child through source changes, validation, pull-request evidence, and REVIEW READY or BLOCKED handoff.",
                        "direct:example-software-team-application-developer-implementation")
                .defaultLane("high-pass")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-software-team-application-developer")
                .routeId("example-software-team-application-developer")
                .to("direct:dev-application-developer-root-enter")
                .setProperty("decision.application-developer-mission-complete.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.application-developer-mission-complete.result").isNotEqualTo(true))
                    .to("direct:dev-project-basics-check")
                    .choice()
                        .when(exchangeProperty("decision.developer-has-project-basics.result").isEqualTo(true))
                            .to("direct:dev-delivery-loop")
                        .otherwise()
                            .to("direct:dev-missing-basics-closeout")
                    .end()
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Review current mission completion status once using the smallest authoritative read.
                                    One unfinished implementation child or product delivery outcome is enough to conclude not
                                    complete. Plane state is authoritative. Do not mutate or begin new development in this check.
                                    """))
                    .to("tack:act?id=dev-mission-status-review&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is the entire mission complete from the application-development perspective?
                                    Return true only when durable current-mission evidence shows no further development,
                                    validation, review response, or implementation follow-up remains.
                                    """))
                    .to("tack:decide?id=application-developer-mission-complete")
                    .filter(exchangeProperty("decision.application-developer-mission-complete.result").isNotEqualTo(true))
                        .delay(120000)
                    .end()
                .end()
                .end()
                .to("direct:dev-application-developer-root-exit");

        from("direct:example-software-team-application-developer-work-selection")
                .routeId("example-software-team-application-developer-work-selection")
                .to("direct:dev-application-developer-root-enter")
                .to("direct:dev-project-basics-check")
                .choice()
                    .when(exchangeProperty("decision.developer-has-project-basics.result").isEqualTo(true))
                        .setProperty("decision.developer-has-work.result", constant(false))
                        .to("direct:dev-work-selection")
                        .choice()
                            .when(exchangeProperty("decision.developer-has-work.result").isNotEqualTo(true))
                                .to("direct:dev-no-work-closeout")
                        .end()
                    .endChoice()
                    .otherwise()
                        .to("direct:dev-missing-basics-closeout")
                .end()
                .to("direct:dev-application-developer-root-exit");

        from("direct:example-software-team-application-developer-implementation")
                .routeId("example-software-team-application-developer-implementation")
                .to("direct:dev-application-developer-root-enter")
                .to("direct:dev-project-basics-check")
                .choice()
                    .when(exchangeProperty("decision.developer-has-project-basics.result").isEqualTo(true))
                        .to("direct:dev-delivery-loop")
                    .otherwise()
                        .to("direct:dev-missing-basics-closeout")
                .end()
                .to("direct:dev-application-developer-root-exit");

        from("direct:dev-application-developer-root-enter")
                .routeId("dev-application-developer-root-enter")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Durable developer anchors: selected implementation child and Plane state, parent product acceptance,
                        source repository, branch/commit/pull request, validation evidence, and current review disposition.
                        """))
                .to("tack:thought-enter?name=application-developer-routine&maxTokens=500");

        from("direct:dev-application-developer-root-exit")
                .routeId("dev-application-developer-root-exit")
                .to("tack:thought-exit?name=application-developer-routine");

        from("direct:dev-project-basics-check")
                .routeId("dev-project-basics-check")
                .setProperty("decision.developer-has-project-basics.result", constant(false))
                .setHeader(TackHeaders.PROMPT, constant("""
                                Verify developer project basics once without mutation. Development requires a usable Plane
                                workspace, source repository under agent-workspace, documentation/current-work navigation,
                                and team chat. Product acceptance is work-specific context, not a shared-project prerequisite;
                                verify it only after selecting an implementation child. One confirmed missing shared basic is
                                enough to stop.
                                """))
                .to("tack:act?id=dev-workspace-basics-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                                Are the shared project basics present and linked enough to select development work?
                                Do not require product work or acceptance links to exist yet.
                                """))
                .to("tack:decide?id=developer-has-project-basics");

        from("direct:dev-missing-basics-closeout")
                .routeId("dev-missing-basics-closeout")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Project basics are not usable for development. Reply once to a relevant setup conversation
                                when useful; otherwise stay quiet. Do not claim work or begin development.
                                """))
                .to("tack:act?id=dev-missing-basics-closeout&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:dev-delivery-loop")
                .routeId("dev-delivery-loop")
                .to("direct:dev-team-chat-sync")
                .setProperty("devProductiveIterations", constant(0))
                .setProperty("devMadeProgress", constant(true))
                .loopDoWhile(simple("${exchangeProperty.devMadeProgress} == true && ${exchangeProperty.devProductiveIterations} < 6"))
                    .setProperty("devProductiveIterations", simple("${exchangeProperty.devProductiveIterations++}"))
                    .setProperty("devMadeProgress", constant(false))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Current developer work unit: one freshly selected assigned implementation child, its real Plane state,
                        parent acceptance, current review disposition, source patch, PR, and validation.
                        """))
                    .to("tack:thought-enter?name=dev-cycle&maxTokens=300")
                    .setProperty("decision.developer-has-work.result", constant(false))
                    .to("direct:dev-work-selection")
                    .choice()
                        .when(exchangeProperty("decision.developer-has-work.result").isEqualTo(true))
                            .to("direct:dev-implementation-loop")
                            .choice()
                                .when(exchangeProperty("devImplementationMadeProgress").isEqualTo(true))
                                    .setProperty("devMadeProgress", constant(true))
                            .endChoice()
                            .to("direct:dev-work-closeout")
                        .otherwise()
                            .to("direct:dev-no-work-closeout")
                    .end()
                    .to("tack:thought-exit?name=dev-cycle")
                .end();

        from("direct:dev-team-chat-sync")
                .routeId("dev-team-chat-sync")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Read recent project chat and join real technical conversations. Answer direct questions,
                                review feedback, or handoffs; if blocked, ask one named question. Do not volunteer for
                                unassigned work or repeat Plane status.
                                """))
                .to("tack:act?id=dev-team-chat-sync&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:dev-work-selection")
                .routeId("dev-work-selection")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Task selection only: one assigned implementation child, not a product parent. Prefer actionable
                        In Progress work, then assigned Todo work. Respect current review/integration wait handoffs.
                        """))
                .to("tack:thought-enter?name=dev-selection&maxTokens=250")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Using fresh Plane evidence, inspect only IMPLEMENTATION CHILDREN assigned to you.

                                First consider assigned In Progress implementation children. Continue one when implementation
                                is actively underway or when a newer PM REVIEW FEEDBACK comment requests changes. A newer
                                REVIEW FEEDBACK beginning SOURCE_REFRESH_REQUIRED is actionable recovery work: update the
                                existing task branch and pull request rather than creating replacements. Prefer current review
                                feedback/recovery over starting another new code task.

                                Do not make additional code changes to an item that is simply waiting on a current REVIEW READY
                                review or a current INTEGRATION READY Director merge with no newer PM feedback.

                                If no actionable assigned In Progress child exists, consider one assigned Todo implementation
                                child. Todo plus assignment is the authoritative PM -> DEV handoff. Verify it references its
                                parent product acceptance, source repository, priority, dependencies, and expected review evidence.
                                If selected, move exactly that implementation child Todo -> In Progress.

                                Ignore Backlog, Done, Cancelled, unassigned work, work assigned to others, and product parents.
                                Never implement a product parent directly.
                                """))
                .to("tack:act?id=dev-work-selection&toolProfile=" + ProjectManagementToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                                Is one assigned actionable implementation child now selected in In Progress?
                                Return false when every assigned In Progress child is waiting on review/integration and no
                                assigned Todo child or newer REVIEW FEEDBACK recovery is available.
                                """))
                .to("tack:decide?id=developer-has-work")
                .to("tack:thought-exit?name=dev-selection");

        from("direct:dev-implementation-loop")
                .routeId("dev-implementation-loop")
                .setProperty("developerImplementationCycle", constant(0))
                .setProperty("devImplementationMadeProgress", constant(false))
                .setProperty("decision.developer-implementation-complete.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.developer-implementation-complete.result").isNotEqualTo(true))
                    .setProperty("developerImplementationCycle", simple("${exchangeProperty.developerImplementationCycle++}"))
                    .setProperty("decision.developer-has-valid-task-context.result", constant(false))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Current implementation step: selected child, parent acceptance behavior, files inspected/changed,
                        branch, commit, pull request, validation, and current review feedback.
                        """))
                    .to("tack:thought-enter?name=dev-implementation&maxTokens=250")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Read only the selected In Progress implementation child using fresh Plane evidence. Verify
                                    assignment, parent acceptance reference, repository, dependencies, expected review evidence,
                                    and the newest PM REVIEW FEEDBACK. Do not mutate in this read. If required delivery context
                                    is malformed or missing, identify the exact PM-owned gap and stop reading.
                                    """))
                    .to("tack:act?id=dev-task-context-read&toolProfile=" + ProjectManagementToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is the selected implementation child's delivery context valid enough to touch source?
                                    Return true only when assignment, parent acceptance reference, source repository, dependency
                                    state, and expected review evidence are sufficiently clear. Return false when any required
                                    context is missing, contradictory, or malformed.
                                    """))
                    .to("tack:decide?id=developer-has-valid-task-context")
                    .choice()
                        .when(exchangeProperty("decision.developer-has-valid-task-context.result").isEqualTo(true))
                            .setHeader(TackHeaders.PROMPT, constant("""
                                            Read only direct product-acceptance links and source files needed for this implementation
                                            child. Identify one coherent next patch and validation target. If current PM feedback requires
                                            source refresh, also inspect the existing task branch/PR against current origin/main so the
                                            recovery patch is based on fresh source state. Stop reading when the patch is clear.
                                            """))
                            .to("tack:act?id=dev-source-context-plan&toolProfile=" + SourceControlToolProfile.ID)
                            .setHeader(TackHeaders.PROMPT, constant("""
                                            Apply exactly one coherent source-control patch for the selected implementation child.

                                            For normal implementation, use or create the task branch, validate changed behavior when
                                            practical, commit, push, and create or update one pull request. Avoid duplicate PRs.

                                            For newer PM REVIEW FEEDBACK beginning SOURCE_REFRESH_REQUIRED, recover the existing task:
                                            - fetch current origin/main
                                            - keep the same task branch and pull request
                                            - merge current origin/main into the task branch; do not rewrite shared history or force-push
                                            - resolve conflicts only when the intended combined behavior is clear from current accepted
                                              requirements and implementation context
                                            - if resolution requires choosing between incompatible product behavior or architecture not
                                              established by current evidence, do not guess; leave the task truthfully blocked for the
                                              appropriate owner
                                            - rerun relevant validation, commit the recovery when needed, and push the same branch

                                            Incorporate all current PM REVIEW FEEDBACK. Do not create replacement tasks or pull requests.
                                            """))
                            .to("tack:act?id=dev-delivery-step&toolProfile=" + SourceControlWriteToolProfile.ID)
                            .choice()
                                .when(exchangeProperty("act.dev-delivery-step.durableProgress").isEqualTo(true))
                                    .setProperty("devImplementationMadeProgress", constant(true))
                            .endChoice()
                            .setHeader(TackHeaders.PROMPT, constant("""
                                            Check fresh validation and reviewability evidence: changed files, current branch/head,
                                            pull request state, tests or other validation actually run, and any limitation.
                                            Do not claim validation that did not occur.
                                            """))
                            .to("tack:act?id=dev-validation-step&toolProfile=" + SourceControlToolProfile.ID)
                            .setHeader(TackHeaders.PROMPT, constant("""
                                            Update exactly the selected implementation child in Plane.

                                            Keep it In Progress. When the implementation child is ready for PM review, add or refresh
                                            one comment beginning REVIEW READY with current branch, commit/head, existing pull request,
                                            changed files, validation result or limitation, parent product item, and canonical acceptance
                                            reference. A source-refresh recovery must publish fresh REVIEW READY for the new head.
                                            REVIEW READY is a handoff comment, not a Plane state. Do not move to Done.

                                            When blocked by implementation evidence, keep the truthful real state and add/refresh one
                                            BLOCKED comment naming the required owner and evidence. Avoid duplicate equivalent handoff comments.
                                            """))
                            .to("tack:act?id=dev-plane-status-evidence-update&toolProfile=" + ProjectManagementToolProfile.ID)
                            .choice()
                                .when(exchangeProperty("act.dev-plane-status-evidence-update.durableProgress").isEqualTo(true))
                                    .setProperty("devImplementationMadeProgress", constant(true))
                            .endChoice()
                        .otherwise()
                            .setHeader(TackHeaders.PROMPT, constant("""
                                            Do not touch source. Keep the selected implementation child In Progress and add or
                                            refresh one BLOCKED comment naming the exact missing or malformed PM-owned delivery
                                            context discovered in the fresh task read and identifying Project Management as the
                                            required owner. Avoid duplicate equivalent comments.
                                            """))
                            .to("tack:act?id=dev-invalid-context-blocked&toolProfile=" + ProjectManagementToolProfile.ID)
                            .choice()
                                .when(exchangeProperty("act.dev-invalid-context-blocked.durableProgress").isEqualTo(true))
                                    .setProperty("devImplementationMadeProgress", constant(true))
                            .endChoice()
                    .end()
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is this implementation step complete for now because current REVIEW READY evidence exists
                                    for a real reviewable diff/PR, or the task is durably BLOCKED? Return false only when there
                                    is an obvious next production-code/test/recovery patch required before review.
                                    """))
                    .to("tack:decide?id=developer-implementation-complete")
                    .to("tack:thought-exit?name=dev-implementation")
                .end();

        from("direct:dev-work-closeout")
                .routeId("dev-work-closeout")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Make one focused documentation improvement only when it preserves durable engineering
                                knowledge for the selected implementation work. Do not create activity logs.
                                """))
                .to("tack:act?id=dev-documentation-closeout&toolProfile=" + SourceControlWriteToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                                Reply in project chat only when review evidence, a blocker, PM feedback, or a teammate's
                                question needs interaction. Avoid generic status.
                                """))
                .to("tack:act?id=dev-team-chat-closeout&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:dev-no-work-closeout")
                .routeId("dev-no-work-closeout")
                .setHeader(TackHeaders.PROMPT, constant("""
                                If no assigned actionable In Progress or Todo implementation child exists, state that fact
                                once in team chat when useful so PM can respond. Do not browse, shape, implement, or self-assign
                                product backlog work.
                                """))
                .to("tack:act?id=dev-no-work-note&toolProfile=" + TeamChatToolProfile.ID);
    }
}
