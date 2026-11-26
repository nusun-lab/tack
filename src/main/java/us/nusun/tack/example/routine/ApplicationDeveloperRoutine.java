package us.nusun.tack.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.tack.example.tool.EngineeringWorkToolProfile;
import us.nusun.tack.example.tool.IntegrationToolProfile;
import us.nusun.tack.example.tool.SourceControlToolProfile;
import us.nusun.tack.example.tool.SourceControlWriteToolProfile;
import us.nusun.tack.example.tool.TeamChatToolProfile;
import us.nusun.tack.example.tool.WorkspaceReadonlyToolProfile;
import us.nusun.tack.framework.routine.RoutineProvider;
import us.nusun.tack.framework.inference.LlmPassType;
import us.nusun.tack.framework.routine.RoutineDefinition;
import us.nusun.tack.framework.routine.TackHeaders;

import java.util.List;

import static org.apache.camel.builder.PredicateBuilder.and;
import static org.apache.camel.builder.PredicateBuilder.or;

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
                            Choose the highest-priority actionable focus.
                            - missing-basics: the verified mission project, authenticated Kaneo user, or required repository baseline is unavailable.
                            - reconcile: your in-progress Story has a verified merged PR and needs done reconciliation.
                            - implementation: advance an actionable assigned in-progress Story/Defect or claim an eligible to-do task.
                              To-do plus assignment is the handoff. An open PR only waiting for merge leaves room for independent work.
                            - integration: help on a safe PR after Architect hands it off or is unavailable, when no implementation is actionable.
                            - coordination: a fresh inbound question needs your judgment.
                            - complete: required Epics are accepted and done and your delivery follow-up is settled.
                            - wait: work awaits another role or new evidence and no independent assigned task is actionable.
                            """))
                    .to("tack:decide?id=dev-focus&choices=missing-basics,reconcile,integration,implementation,coordination,complete,wait")
                    .toD("direct:dev-focus-${exchangeProperty.decision.dev-focus.result}")
                .end()
                .end()
                .to("direct:dev-application-developer-root-exit");

        from("direct:dev-focus-missing-basics")
                .routeId("dev-focus-missing-basics")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Exact locator and actual lookup evidence for an unavailable prerequisite. Keep the owner and next condition.
                        """))
                .to("tack:thought-enter?name=dev-missing-basics-focus&maxTokens=250")
                .to("direct:dev-missing-basics-closeout")
                .to("tack:thought-exit?name=dev-missing-basics-focus");

        from("direct:dev-focus-reconcile")
                .routeId("dev-focus-reconcile")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Reconcile exactly one assigned story whose linked PR is verified merged.
                        Confirm the selected Story is still in-progress, the PR/commit is actually merged to the intended
                        base, and no newer evidence requires source changes. Move that Story to done and preserve concise
                        merge/validation evidence. Never close defect here; repaired Defects remain in-progress for QA
                        retest and closure.
                        """))
                .to("tack:act?id=dev-story-reconcile&toolProfile=" + EngineeringWorkToolProfile.ID)
                .to("direct:dev-work-closeout-no-source");

        from("direct:dev-focus-implementation")
                .routeId("dev-focus-implementation")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Exact task/Epic and assignee IDs, repo owner/name, branch/PR, validation and remaining blocker.
                        """))
                .to("tack:thought-enter?name=dev-implementation-focus&maxTokens=450")
                .to("direct:dev-implementation-focus")
                .to("tack:thought-exit?name=dev-implementation-focus");

        from("direct:dev-focus-integration")
                .routeId("dev-focus-integration")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Integrate one safe current-mission PR only after a fresh explicit Architect handoff or when
                        Architect is unavailable. Fresh Gitea evidence must show it is open, targets the intended base,
                        is mergeable/non-conflicted, sufficiently validated, and has no unresolved product/QA concern.
                        Architect remains the primary integration owner; this is fallback help, not a reciprocal gate.
                        Never require Director approval, QA "green",
                        or another chat phrase. If evidence is unsafe or ambiguous, do not merge and report the concrete
                        blocker to Architect.
                        """))
                .to("tack:act?id=dev-architect-pr-integration&toolProfile=" + IntegrationToolProfile.ID)
                .to("direct:dev-work-closeout-no-source");

        from("direct:dev-focus-coordination")
                .routeId("dev-focus-coordination")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Exact channel/thread and linked work locators for one actionable technical exchange.
                        """))
                .to("tack:thought-enter?name=dev-coordination-focus&maxTokens=250")
                .to("direct:dev-team-chat-sync")
                .to("tack:thought-exit?name=dev-coordination-focus");

        from("direct:dev-focus-complete")
                .routeId("dev-focus-complete")
                .setProperty("decision.application-developer-mission-complete.result", constant("yes"));

        from("direct:dev-focus-wait")
                .routeId("dev-focus-wait")
                .delay(120000);

        from("direct:dev-orientation")
                .routeId("dev-orientation")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Verify the mission project and your Kaneo user via whoami. Filter the project task list for your ID
                        in in-progress and to-do. Use exact story/defect labels. For relevant in-progress work, check linked
                        Gitea PRs to distinguish actionable work, integration wait, merged Story reconciliation and Defects
                        awaiting QA. Use known repository locators and identify a missing baseline only with actual evidence.
                        Report the smallest evidence needed for the next focus. Chat is optional awareness.
                        """))
                .to("tack:act?id=dev-orientation&toolProfile=" + WorkspaceReadonlyToolProfile.ID);

        from("direct:dev-implementation-focus")
                .routeId("dev-implementation-focus")
                .setProperty("decision.developer-has-work.result", constant("no"))
                .setProperty("devSourceCloseoutAllowed", constant(false))
                .to("direct:dev-work-selection")
                .choice()
                    .when(exchangeProperty("decision.developer-has-work.result").isEqualTo("yes"))
                        .to("direct:dev-implementation-loop")
                        .to("direct:dev-work-closeout-dispatch")
                    .otherwise()
                        .to("direct:dev-no-work-closeout")
                .end();

        from("direct:example-software-team-application-developer-work-selection")
                .routeId("example-software-team-application-developer-work-selection")
                .to("direct:dev-application-developer-root-enter")
                .to("direct:dev-project-basics-check")
                .choice()
                    .when(exchangeProperty("decision.developer-has-project-basics.result").isEqualTo("yes"))
                        .setProperty("decision.developer-has-work.result", constant("no"))
                        .to("direct:dev-work-selection")
                        .choice()
                            .when(exchangeProperty("decision.developer-has-work.result").isNotEqualTo("yes"))
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
                    .when(exchangeProperty("decision.developer-has-project-basics.result").isEqualTo("yes"))
                        .to("direct:dev-delivery-loop")
                    .otherwise()
                        .to("direct:dev-missing-basics-closeout")
                .end()
                .to("direct:dev-application-developer-root-exit");

        from("direct:dev-application-developer-root-enter")
                .routeId("dev-application-developer-root-enter")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Mission anchor, verified Kaneo workspace ID, project ID/slug and DEV user ID, Mattermost team/channel IDs,
                        repository owner/repo pairs and exact selected task/Epic and branch/PR locators. Keep validation and open blockers.
                        """))
                .to("tack:thought-enter?name=application-developer-routine&maxTokens=500");

        from("direct:dev-application-developer-root-exit")
                .routeId("dev-application-developer-root-exit")
                .to("tack:thought-exit?name=application-developer-routine");

        from("direct:dev-project-basics-check")
                .routeId("dev-project-basics-check")
                .setProperty("decision.developer-has-project-basics.result", constant("no"))
                .setHeader(TackHeaders.PROMPT, constant("""
                        Verify the mission project and authenticated user. List assigned in-progress and to-do work. Check
                        linked PR status where needed and resolve the Architect's repository map and main baseline.
                        """))
                .to("tack:act?id=dev-workspace-basics-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Can the Developer access the mission project and authenticated identity, and find the documented
                        repository map and required baseline? Answer from verified evidence.
                        """))
                .to("tack:decide?id=developer-has-project-basics");

        from("direct:dev-missing-basics-closeout")
                .routeId("dev-missing-basics-closeout")
                .setHeader(TackHeaders.PROMPT, constant("""
                        A concrete project basic is actually inaccessible. Initiate or reply to one concise setup
                        question only when it would help PM/ARCH repair that concrete access/basic sooner. An
                        empty/uninitialized assigned repository IS a repository-architecture blocker; untested push
                        permission is not. Do not claim work when a required baseline cannot be accessed.
                        """))
                .to("tack:act?id=dev-missing-basics-closeout&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:dev-delivery-loop")
                .routeId("dev-delivery-loop")
                .setProperty("devProductiveIterations", constant(0))
                .loopDoWhile(and(exchangeProperty("devProductiveIterations").isLessThan(6),
                        or(exchangeProperty("devProductiveIterations").isEqualTo(0),
                            exchangeProperty("devImplementationMadeProgress").isEqualTo(true))))
                    .setProperty("devProductiveIterations",
                            simple("${exchangeProperty.devProductiveIterations}++", Integer.class))
                    .setProperty("devImplementationMadeProgress", constant(false))
                    .setProperty("devSourceCloseoutAllowed", constant(false))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                            Exact selected task/Epic and PR locators, current status, validation and next action.
                            """))
                    .to("tack:thought-enter?name=dev-cycle&maxTokens=300")
                    .setProperty("decision.developer-has-work.result", constant("no"))
                    .to("direct:dev-work-selection")
                    .choice()
                        .when(exchangeProperty("decision.developer-has-work.result").isEqualTo("yes"))
                            .to("direct:dev-implementation-loop")
                            .to("direct:dev-work-closeout-dispatch")
                        .otherwise()
                            .to("direct:dev-no-work-closeout")
                    .end()
                    .to("tack:thought-exit?name=dev-cycle")
                .end();

        from("direct:dev-work-closeout-dispatch")
                .routeId("dev-work-closeout-dispatch")
                .choice()
                    .when(exchangeProperty("devSourceCloseoutAllowed").isEqualTo(true))
                        .to("direct:dev-work-closeout")
                    .otherwise()
                        .to("direct:dev-work-closeout-no-source")
                .end();

        from("direct:dev-team-chat-sync")
                .routeId("dev-team-chat-sync")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read only recent project chat needed for one useful technical exchange. Do not require an
                        existing conversation before posting. Initiate one concise message when a new/updated PR, an
                        ACTUAL implementation/access failure, technical finding, validation limitation, or named
                        question would help PM/PO/ARCH/QA/Director act sooner; otherwise answer one fresh technical question. Do
                        not ask PM to re-announce an assignment already represented by Kaneo to-do + assignee. Link
                        authoritative Kaneo/Gitea evidence when useful. Do not volunteer
                        for unassigned work or repeat generic status.
                        """))
                .to("tack:act?id=dev-team-chat-sync&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:dev-work-selection")
                .routeId("dev-work-selection")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Exact task, Epic, assignee and repository locators for one eligible unit. Keep claim status and blockers.
                        """))
                .to("tack:thought-enter?name=dev-selection&maxTokens=250")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Use the verified mission project and your whoami user ID. List assigned in-progress and to-do tasks.
                        Select one verified Story or Defect, preferring actionable in-progress work and then repair work
                        when options are otherwise equivalent. Use or recover exact Gitea PR evidence to skip integration waits,
                        merged Stories awaiting reconciliation, and merged Defects awaiting QA.
                        Verify the Epic relation and acceptance intent and that incoming prerequisites are done. A Story
                        requires an in-progress Epic. For a topology investigation require an explicit technical question.
                        To-do plus assignment is the complete PM handoff. Claim the selected to-do task using update_task_status.
                        If PR evidence for an in-progress candidate is unknown, leave it unresolved and consider eligible to-do work.
                        """))
                .to("tack:act?id=dev-work-selection&toolProfile=" + EngineeringWorkToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Is one assigned actionable defect or story now selected in-progress? Answer yes for
                        active normal implementation/recovery or explicit topology investigation. An open PR waiting only
                        on integration is not actionable implementation, but it does not make another assigned to-do
                        ineligible. Answer no only when no actionable in-progress or eligible assigned to-do source unit exists.
                        """))
                .to("tack:decide?id=developer-has-work")
                .to("tack:thought-exit?name=dev-selection");

        from("direct:dev-implementation-loop")
                .routeId("dev-implementation-loop")
                .setProperty("developerImplementationCycle", constant(0))
                .setProperty("devImplementationMadeProgress", constant(false))
                .setProperty("devImplementationStepProgress", constant(true))
                .setProperty("decision.developer-implementation-complete.result", constant("no"))
                .loopDoWhile(simple("${exchangeProperty.devImplementationStepProgress} == true && ${exchangeProperty.developerImplementationCycle} < 6"))
                    .setProperty("developerImplementationCycle",
                            simple("${exchangeProperty.developerImplementationCycle}++", Integer.class))
                    .setProperty("devImplementationStepProgress", constant(false))
                    .setProperty("decision.developer-has-valid-task-context.result", constant("no"))
                    .setProperty("decision.developer-is-topology-investigation.result", constant("no"))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                            Exact task and repo/branch/PR locators, validation and any concrete blocker or technical finding.
                            """))
                    .to("tack:thought-enter?name=dev-implementation&maxTokens=250")
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Reuse the selected task, verified classification and Epic context. Read only missing details:
                            implementation intent, exact repository, validation target, or newer feedback. Preserve any concrete
                            blocker for the role that can resolve it.
                            """))
                    .to("tack:act?id=dev-task-context-read&toolProfile=" + EngineeringWorkToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Is selected source-work context valid enough to perform assigned work? Normal delivery requires
                            assignment, exact defect/story classification, Epic acceptance, repository locator,
                            dependency state, expected validation evidence, and the Architect-established main baseline.
                            A missing feature branch, PM chat confirmation, or speculative push permission is not a blocker.
                            Topology investigation requires
                            assignment, repository, dependency-free state, affected work ids, acceptance refs, exact
                            technical question. Answer no on genuinely missing/contradictory required context.
                            """))
                    .to("tack:decide?id=developer-has-valid-task-context")
                    .choice()
                        .when(exchangeProperty("decision.developer-has-valid-task-context.result").isEqualTo("yes"))
                            .setHeader(TackHeaders.PROMPT, constant("""
                                    Is this selected implementation child explicitly a topology investigation rather than normal
                                    source delivery? Answer yes only when current task description clearly asks for evidence
                                    gathering and an exact technical sequencing/integration question.
                                    """))
                            .to("tack:decide?id=developer-is-topology-investigation")
                            .choice()
                                .when(exchangeProperty("decision.developer-is-topology-investigation.result").isEqualTo("yes"))
                                    .setHeader(TackHeaders.PROMPT, constant("""
                                            Investigate only source/docs/affected Kaneo work needed to answer the exact topology question.
                                            Trace referenced dependencies and source boundaries. Do not change source merely to create
                                            progress. Stop when requested facts are established or
                                            remaining uncertainty is clearly product-owned.
                                            """))
                                    .to("tack:act?id=dev-topology-investigation&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                                    .setHeader(TackHeaders.PROMPT, constant("""
                                            Update only the selected topology-investigation child. Keep it in-progress and add one
                                            concise evidence comment containing affected work ids, observed source/contract facts,
                                            evidence-backed dependency/sequencing recommendation, and residual product question if any.
                                            PM owns dependency/topology mutation, so do not restructure other tasks yourself. Do not
                                            encode a named TECHNICAL FINDING workflow state; avoid duplicate equivalent evidence.
                                            """))
                                    .to("tack:act?id=dev-topology-finding-update&toolProfile=" + EngineeringWorkToolProfile.ID)
                                    .choice()
                                        .when(exchangeProperty("act.dev-topology-finding-update.durableProgress").isEqualTo(true))
                                            .setProperty("devImplementationMadeProgress", constant(true))
                                            .setProperty("devImplementationStepProgress", constant(true))
                                    .end().endChoice()
                                .otherwise()
                                    .setProperty("devSourceCloseoutAllowed", constant(true))
                                    .setHeader(TackHeaders.PROMPT, constant("""
                                            Read only direct product-acceptance links and existing source needed for this child. Identify
                                            one coherent next patch and validation target. The Architect-established
                                            repository baseline must already exist; do not initialize repository structure
                                            here. If newer concrete review feedback requires source refresh, inspect the
                                            existing task branch/PR against the current baseline. Stop when patch is clear.
                                            """))
                                    .to("tack:act?id=dev-source-context-plan&toolProfile=" + SourceControlToolProfile.ID)
                                    .setHeader(TackHeaders.PROMPT, constant("""
                                        Apply exactly one coherent source-control patch for the selected implementation
                                        child in the existing Architect-established repository. Use/create the task branch,
                                        validate changed behavior when practical, commit, push, and create/update one PR;
                                        avoid duplicate PRs. Do not initialize or provision repository architecture. Do not
                                        preflight hypothetical collaborator
                                        permission. Attempt the ordinary push/PR workflow; only a real auth/authorization rejection is a
                                        blocker. For evidence-backed source-refresh feedback, recover the same task branch against
                                        current origin/main when present, rerun relevant validation, and reuse the same open PR when
                                        possible. If resolution requires incompatible product behavior, do not guess; leave concise
                                        blocker evidence for Product Owner/PM. Do not create replacement
                                        tasks or branches merely to advance workflow.
                                        """))
                                    .to("tack:act?id=dev-delivery-step&toolProfile=" + SourceControlWriteToolProfile.ID)
                                    .choice()
                                        .when(exchangeProperty("act.dev-delivery-step.durableProgress").isEqualTo(true))
                                            .setProperty("devImplementationMadeProgress", constant(true))
                                            .setProperty("devImplementationStepProgress", constant(true))
                                    .end()
                                    .setHeader(TackHeaders.PROMPT, constant("""
                                            Check fresh validation/reviewability evidence: changed files, current branch/head, exact PR
                                            state, tests/validation actually run, and limitations.
                                            Do not claim validation that did not occur.
                                            """))
                                    .to("tack:act?id=dev-validation-step&toolProfile=" + SourceControlToolProfile.ID)
                                    .setHeader(TackHeaders.PROMPT, constant("""
                                        Record new source or validation evidence on the selected task with exact branch, head and PR.
                                        Keep it in-progress during integration. After verified merge, reconcile Stories to done in the
                                        reconcile focus. QA owns Defect retest and closure.
                                        """))
                                    .to("tack:act?id=dev-task-evidence-update&toolProfile=" + EngineeringWorkToolProfile.ID)
                                    .choice()
                                        .when(exchangeProperty("act.dev-task-evidence-update.durableProgress").isEqualTo(true))
                                            .setProperty("devImplementationMadeProgress", constant(true))
                                            .setProperty("devImplementationStepProgress", constant(true))
                                    .end()
                            .end().endChoice()
                        .otherwise()
                            .setProperty("devSourceCloseoutAllowed", constant(false))
                            .setHeader(TackHeaders.PROMPT, constant("""
                                    Do not touch source. Keep selected child in-progress and add one concise evidence
                                    comment naming the observed gap and its owner. Architect owns any missing or incorrect main
                                    baseline. PM owns missing or malformed delivery context. Untested source permission and missing
                                    Mattermost confirmation are not blockers. Do not encode a named BLOCKED workflow state.
                                    """))
                            .to("tack:act?id=dev-invalid-context-blocked&toolProfile=" + EngineeringWorkToolProfile.ID)
                            .choice()
                                .when(exchangeProperty("act.dev-invalid-context-blocked.durableProgress").isEqualTo(true))
                                    .setProperty("devImplementationMadeProgress", constant(true))
                                    .setProperty("devImplementationStepProgress", constant(true))
                            .end()
                    .end()
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Is this implementation step complete for now because normal source delivery has an open
                            reviewable PR with current validation evidence, topology investigation has recorded the
                            requested evidence, or a concrete observed blocker has been durably recorded? Answer no only
                            when an obvious next production-code/test/recovery/investigation step
                            is still required before that natural stop.
                            """))
                    .to("tack:decide?id=developer-implementation-complete")
                    .choice()
                        .when(exchangeProperty("decision.developer-implementation-complete.result").isEqualTo("yes"))
                            .setProperty("devImplementationStepProgress", constant(false))
                    .end()
                    .to("tack:thought-exit?name=dev-implementation")
                .end();

        from("direct:dev-work-closeout")
                .routeId("dev-work-closeout")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Make one focused documentation improvement only when it preserves durable engineering knowledge
                        for this valid implementation work. Do not create activity logs.
                        """))
                .to("tack:act?id=dev-documentation-closeout&toolProfile=" + SourceControlWriteToolProfile.ID)
                .to("direct:dev-work-closeout-no-source");

        from("direct:dev-work-closeout-no-source")
                .routeId("dev-work-closeout-no-source")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Initiate or reply to one concise project-chat message when a new/updated PR, real blocker,
                        topology evidence, validation limitation, teammate feedback, or direct question would help
                        another role act sooner. Do not require an existing thread. Avoid generic status.
                        This closeout has no source-write capability.
                        """))
                .to("tack:act?id=dev-team-chat-closeout&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:dev-no-work-closeout")
                .routeId("dev-no-work-closeout")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Send a concise message only when new queue or access evidence helps PM act. Avoid repeating a
                        previous note or requesting another handoff for an already assigned to-do task.
                        """))
                .to("tack:act?id=dev-no-work-note&toolProfile=" + TeamChatToolProfile.ID);
    }
}
