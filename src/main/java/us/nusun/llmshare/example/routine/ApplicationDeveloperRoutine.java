package us.nusun.llmshare.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.example.tool.EngineeringWorkToolProfile;
import us.nusun.llmshare.example.tool.IntegrationToolProfile;
import us.nusun.llmshare.example.tool.SourceControlToolProfile;
import us.nusun.llmshare.example.tool.SourceControlWriteToolProfile;
import us.nusun.llmshare.example.tool.TeamChatToolProfile;
import us.nusun.llmshare.example.tool.WorkspaceReadonlyToolProfile;
import us.nusun.llmshare.framework.inventory.RoutineProvider;
import us.nusun.llmshare.framework.model.LlmPassType;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.tack.TackHeaders;

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
                            Choose exactly one highest-priority Application Developer focus for the next focused pass.
                            Allowed focus values, in priority order when genuinely actionable:
                            - missing-basics: the Developer cannot access the current-mission Plane project, Architect-
                              established component repository baseline, system docs/navigation, or mission chat. An
                              empty/uninitialized repository means repository architecture is not ready for Developer work.
                            - reconcile: an assigned story remains In Progress after its linked PR
                              is verified merged; Developer owns its transition to Done.
                            - implementation: an assigned defect or story In Progress has actionable
                              implementation/recovery work, or an assigned Todo source unit is ready to start. Maintain a
                              practical flow target of roughly 2-3 actively coded source units when enough independent work
                              exists. A Story/Defect whose PR is open and needs no further source change is integration-wait,
                              not active coding WIP, and never blocks claiming another assigned Todo source unit.
                            - integration: Architect explicitly handed off a safe current-mission PR for Developer help, or
                              Architect is unavailable and a mechanically safe, sufficiently validated PR can be integrated.
                              Architect remains the primary integration owner; fallback integration does not outrank useful
                              assigned implementation work.
                              Todo + Developer assignment is
                              the complete authoritative PM -> DEV handoff. Developer owns Todo -> In Progress. A useful
                              PM awareness message may provide context but never gates work. Do not wait for chat
                              confirmation, PM to flip state, PM to seed main, or
                              speculative collaborator-grant confirmation.
                            - coordination: a fresh inbound technical conversation/direct question needs Developer
                              participation outside the selected implementation flow. Outbound PR/blocker/finding
                              awareness belongs to implementation closeout and does not compete for focus.
                            - complete: every required product Epic is terminal and no Developer-owned Story/Defect, merged
                              Story awaiting Done, validation, open PR needing Developer changes, or implementation
                              follow-up remains.
                            - wait: the mission is not complete and no useful assigned defect/story item
                              is currently available, or a concrete required basic is unavailable. Open unmerged PRs do not
                              justify waiting when another independent assigned Todo source unit is executable. Comments are
                              evidence/context only; do not wait for REVIEW READY, INTEGRATION READY, or another magic
                              comment state. An empty/uninitialized repo is an Architect/PM setup blocker,
                              not a Developer bootstrap task. Only an actual failed source operation establishes a
                              permission/authentication blocker. Fresh
                              orientation evidence outranks older wait conclusions. Choose exactly one allowed focus value.
                            """))
                    .to("tack:decide?id=dev-focus&choices=missing-basics,reconcile,integration,implementation,coordination,complete,wait")
                    .toD("direct:dev-focus-${exchangeProperty.decision.dev-focus.result}")
                .end()
                .end()
                .to("direct:dev-application-developer-root-exit");

        from("direct:dev-focus-missing-basics")
                .routeId("dev-focus-missing-basics")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Focused missing-foundation response: communicate only when a concrete access/basic or Architect-
                        established repository baseline is actually unavailable.
                        """))
                .to("tack:thought-enter?name=dev-missing-basics-focus&maxTokens=250")
                .to("direct:dev-missing-basics-closeout")
                .to("tack:thought-exit?name=dev-missing-basics-focus");

        from("direct:dev-focus-reconcile")
                .routeId("dev-focus-reconcile")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Reconcile exactly one assigned story whose linked PR is verified merged.
                        Confirm the selected Story is still In Progress, the PR/commit is actually merged to the intended
                        base, and no newer evidence requires source changes. Move that Story to Done and preserve concise
                        merge/validation evidence. Never close defect here; repaired Defects remain In Progress for QA
                        retest and closure.
                        """))
                .to("tack:act?id=dev-story-reconcile&toolProfile=" + EngineeringWorkToolProfile.ID)
                .to("direct:dev-work-closeout-no-source");

        from("direct:dev-focus-implementation")
                .routeId("dev-focus-implementation")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Focused implementation unit: one assigned defect or story, task/product context,
                        source or topology work, validation, PR state, and concise durable evidence.
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
                        Focused Developer coordination for one fresh inbound technical question or teammate exchange.
                        Implementation closeout handles outbound PR/blocker/finding awareness.
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
                        Read the smallest fresh current-mission slice needed to rank Developer focus. Determine only:
                        whether shared basics are accessible; up to three assigned source work units In Progress,
                        distinguishing active coding from PRs merely waiting on integration; whether linked PRs are
                        open/merged/absent and newer concrete feedback requires more implementation; whether enough assigned
                        defect/story Todo units exist to keep roughly 2-3 executable units available; one fresh
                        inbound technical exchange needing attention; and
                        completion plausibility. Require an Architect-established non-empty repository baseline for
                        Developer work. Do not treat untested push permission as a blocker. Prefer verified
                        project/repo/work-item ids already in thought rather than broad listing. Plane is authoritative
                        for ownership/lifecycle/classification; Gitea is authoritative for PR state. Report observations
                        only. Do not recommend a next focus, preserve older HOLD/wait conclusions against newer
                        evidence, mutate, select, or implement work here.
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
                        Durable developer anchors: verified project/repo/chat ids, selected source work
                        (defect or story)/Plane state, Epic acceptance,
                        topology question when present, branch/commit/PR,
                        validation evidence, blockers/feedback, and current PR state.
                        """))
                .to("tack:thought-enter?name=application-developer-routine&maxTokens=500");

        from("direct:dev-application-developer-root-exit")
                .routeId("dev-application-developer-root-exit")
                .to("tack:thought-exit?name=application-developer-routine");

        from("direct:dev-project-basics-check")
                .routeId("dev-project-basics-check")
                .setProperty("decision.developer-has-project-basics.result", constant("no"))
                .setHeader(TackHeaders.PROMPT, constant("""
                        Verify developer project basics once without mutation: accessible Plane project, source
                        repository, docs/navigation, and team chat. Prefer verified ids already in thought. The assigned
                        repository must have the Architect-established non-empty main baseline before Developer work.
                        An empty repository, no main branch, or feature branch as the only/default branch is an Architect
                        repository-bootstrap problem, not Developer initialization work. Do not require product acceptance
                        yet, a PM chat handoff, or speculative Gitea
                        permission confirmation. One actual inaccessible basic is enough to stop.
                        """))
                .to("tack:act?id=dev-workspace-basics-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Are shared project basics accessible enough to select development work? Require access to the
                        Architect-established component repository baseline and system documentation. Do not require
                        pre-confirmed push permission.
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
                .to("direct:dev-team-chat-sync")
                .setProperty("devProductiveIterations", constant(0))
                .setProperty("devMadeProgress", constant(true))
                .loopDoWhile(simple("${exchangeProperty.devMadeProgress} == true && ${exchangeProperty.devProductiveIterations} < 6"))
                    .setProperty("devProductiveIterations", simple("${exchangeProperty.devProductiveIterations++}"))
                    .setProperty("devMadeProgress", constant(false))
                    .setProperty("devSourceCloseoutAllowed", constant(false))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                            Current developer work unit: one selected defect or story,
                            real Plane state, Epic acceptance, topology question when applicable, source evidence, patch/PR,
                            validation, and current feedback/blocker evidence.
                            """))
                    .to("tack:thought-enter?name=dev-cycle&maxTokens=300")
                    .setProperty("decision.developer-has-work.result", constant("no"))
                    .to("direct:dev-work-selection")
                    .choice()
                        .when(exchangeProperty("decision.developer-has-work.result").isEqualTo("yes"))
                            .to("direct:dev-implementation-loop")
                            .setProperty("devMadeProgress", exchangeProperty("devImplementationMadeProgress"))
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
                        not ask PM to re-announce an assignment already represented by Plane Todo + assignee. Link
                        authoritative Plane/Gitea evidence when useful. Do not volunteer
                        for unassigned work or repeat generic status.
                        """))
                .to("tack:act?id=dev-team-chat-sync&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:dev-work-selection")
                .routeId("dev-work-selection")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Work selection only: one assigned defect or story, never Epics or test. Prefer actionable
                        In Progress work; otherwise claim an assigned Todo when useful.
                        Maintain roughly 2-3 actively coded source units when enough independent work exists. Open PRs
                        waiting only on integration do not consume active coding capacity. Todo + assignment is complete
                        handoff; Developer claims Todo -> In Progress.
                        """))
                .to("tack:thought-enter?name=dev-selection&maxTokens=250")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Using fresh Plane evidence, inspect only items classified defect or story and
                        assigned to you.
                        Never select epic product outcomes or test. Require story for implementation delivery or defect for repair. If classification
                        were not returned, read
                        classification instead of guessing from wording.
                        
                        First consider assigned In Progress source work. Prioritize defects over
                        story Stories when both are actionable. Continue one when
                        implementation/topology investigation is underway or newer concrete teammate feedback/evidence
                        requires another pass. For normal source delivery, inspect the linked Gitea PR only as needed:
                        if the task's PR is OPEN and no newer evidence requires changes, the task is waiting for
                        Architect integration and is not actionable source work.
                        Comments do not create review/integration states.
                        
                        An In Progress unit with an OPEN unmerged PR and no newer required source change is integration-wait
                        and is not actionable source work, but it does not block another independent assigned Todo unit.
                        A merged story belongs to reconcile focus, not source implementation.
                        A merged defect waits for QA retest/closure and is not actionable source work.
                        
                        If no higher-priority actionable In Progress child should be advanced, select one assigned Todo
                        source unit, preferring defect before story. For a story,
                        verify its parent Epic is In Progress before claiming it; children beneath Todo/Backlog Epics are
                        prepared future work, not executable delivery. Keep roughly 2-3 active coding units when enough
                        independent work exists.
                        Todo + assignment is the authoritative PM ->
                        DEV handoff. Do NOT wait for PM to repeat the handoff in Mattermost or move it In Progress. Move
                        exactly that selected child Todo -> In Progress yourself. A topology investigation may be
                        selected when explicitly described as evidence-gathering
                        and dependency-free with an exact question.
                        
                        Ignore Backlog, Done, Cancelled, unassigned work, work
                        assigned to others, product Epics, and test items.
                        """))
                .to("tack:act?id=dev-work-selection&toolProfile=" + EngineeringWorkToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Is one assigned actionable defect or story now selected In Progress? Answer yes for
                        active normal implementation/recovery or explicit topology investigation. An open PR waiting only
                        on integration is not actionable implementation, but it does not make another assigned Todo
                        ineligible. Answer no only when no actionable In Progress or eligible assigned Todo source unit exists.
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
                    .setProperty("developerImplementationCycle", simple("${exchangeProperty.developerImplementationCycle++}"))
                    .setProperty("devImplementationStepProgress", constant(false))
                    .setProperty("decision.developer-has-valid-task-context.result", constant("no"))
                    .setProperty("decision.developer-is-topology-investigation.result", constant("no"))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                            Current implementation step: selected source work unit, classification/type, Epic acceptance
                            behavior, source evidence, branch/PR, validation, current teammate feedback, blocker
                            evidence, and technical finding when applicable.
                            """))
                    .to("tack:thought-enter?name=dev-implementation&maxTokens=250")
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Read only the selected In Progress source work unit. Verify Developer assignment, exact defect or
                            story classification, associated Epic acceptance reference, repo,
                            dependencies, expected validation evidence, and any newer concrete feedback/blocker context. For
                            topology investigation verify dependency-free exact technical question. The assigned
                            repository must already contain the Architect-established baseline. Do not mutate. If required
                            delivery context
                            is malformed/missing, identify the exact PM-owned gap and stop reading. Keep claimed work In
                            Progress when a later blocker needs PM/PO action; evidence is the signal, not state rollback.
                            """))
                    .to("tack:act?id=dev-task-context-read&toolProfile=" + EngineeringWorkToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Is selected source-work context valid enough to perform assigned work? Normal delivery requires
                            assignment, exact defect/story classification, Epic acceptance, repository locator,
                            dependency state, and expected validation evidence. It does NOT require an existing branch/main,
                            PM chat confirmation, or pre-confirmed push permission. Topology investigation requires
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
                                            Investigate only source/docs/affected Plane work needed to answer the exact topology question.
                                            Trace referenced dependencies and source boundaries. Do not change source merely to create
                                            progress. Stop when requested facts are established or
                                            remaining uncertainty is clearly product-owned.
                                            """))
                                    .to("tack:act?id=dev-topology-investigation&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                                    .setHeader(TackHeaders.PROMPT, constant("""
                                            Update only the selected topology-investigation child. Keep it In Progress and add/refresh one
                                            concise evidence comment containing affected work ids, observed source/contract facts,
                                            evidence-backed dependency/sequencing recommendation, and residual product question if any.
                                            PM owns dependency/topology mutation, so do not restructure other work items yourself. Do not
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
                                        Update only the selected source work unit in Plane. Keep it In Progress while its PR is
                                        open/unmerged. Add or refresh one concise evidence/context comment only when useful, linking
                                        current branch/head/PR, changed files, validation, epic, acceptance ref, or exact
                                        blocker. Do NOT create REVIEW READY, INTEGRATION READY, or other named comment workflow states.
                                        The open PR itself is the integration signal for Architect. For blockers, name only a real
                                        observed missing product judgment, malformed delivery context, failed source/auth operation,
                                        technical evidence, or external condition and who can resolve it. Avoid duplicate equivalent
                                        comments. Do not move to Done here. After merge, Developer reconciles its assigned
                                        story to Done; defects remain In Progress for QA
                                        retest/closure.
                                        """))
                                    .to("tack:act?id=dev-plane-status-evidence-update&toolProfile=" + EngineeringWorkToolProfile.ID)
                                    .choice()
                                        .when(exchangeProperty("act.dev-plane-status-evidence-update.durableProgress").isEqualTo(true))
                                            .setProperty("devImplementationMadeProgress", constant(true))
                                            .setProperty("devImplementationStepProgress", constant(true))
                                    .end()
                            .end().endChoice()
                        .otherwise()
                            .setProperty("devSourceCloseoutAllowed", constant(false))
                            .setHeader(TackHeaders.PROMPT, constant("""
                                    Do not touch source. Keep selected child In Progress and add/refresh one concise evidence
                                    comment naming the genuinely missing/malformed PM-owned delivery context and required repair. Do
                                    not use absent main/default branch, untested source permission, or missing Mattermost
                                    confirmation as blockers. Do not encode a named BLOCKED workflow state in comments.
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
                        If no assigned actionable defect/story In Progress/Todo unit exists, send one concise
                        message only when PM would benefit from knowing the executable queue is empty or actual access
                        is blocking work. Do not ask PM to re-announce an assignment already represented in Plane. Do
                        not browse, shape, implement, or self-assign product backlog
                        work. Avoid repeating the same no-work message.
                        """))
                .to("tack:act?id=dev-no-work-note&toolProfile=" + TeamChatToolProfile.ID);
    }
}
