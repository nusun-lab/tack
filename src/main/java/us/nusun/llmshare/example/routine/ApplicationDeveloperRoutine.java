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
    public List<RoutineDefinition> routines() { return List.of(routine()); }

    public RoutineDefinition routine() {
        return RoutineDefinition.builder(ID)
                .name("Application Developer")
                .main("Deliver assigned implementation work through completion.", "direct:example-software-team-application-developer")
                .subroutine("work-selection", "Select the next assigned implementation task.", "direct:example-software-team-application-developer-work-selection")
                .subroutine("implementation", "Implement and validate one assigned task.", "direct:example-software-team-application-developer-implementation")
                .defaultLane("high-pass")
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
                            - missing-basics: the Developer cannot access the current-mission Plane project, source repository,
                              docs/navigation, or mission chat. An existing accessible source repository is usable even when empty and has
                              no main/default branch yet.
                            - implementation: one assigned work:implementation In Progress child has actionable implementation/recovery
                              work, or no current source-delivery child has an open unmerged PR and one assigned work:implementation Todo
                              child is ready to start. Todo + Developer assignment is the complete PM -> DEV handoff. Developer owns Todo
                              -> In Progress. Do not wait for PM chat confirmation, PM to flip state, PM to seed main, or speculative
                              collaborator-grant confirmation.
                            - coordination: there is a useful technical/blocker/PR notification or question to initiate, or a fresh
                              technical conversation/direct question needs Developer participation outside the selected implementation
                              flow.
                            - completion: durable evidence makes development-side mission completion plausible enough for the dedicated
                              completion check.
                            - wait: current assigned source work has an open unmerged PR and no newer evidence requires another source
                              change, or no useful assigned work:implementation item is currently available. Do not start a second source
                              unit while one task PR remains open/unmerged.
                            Keep unmerged source WIP to one integration unit. This constraint does NOT limit PO or PM backlog depth.
                            Comments are evidence/context only; do not wait for REVIEW READY, INTEGRATION READY, or another magic comment
                            state. An empty repo is a starting condition, not a blocker. Only an actual failed source operation
                            establishes a permission/authentication blocker. Fresh orientation evidence outranks older wait conclusions.
                            Choose exactly one allowed focus value.
                            """))
                    .to("tack:decide?id=dev-priority-focus&choices=missing-basics,implementation,review-fix,completion,wait")
                    .choice()
                        .when(exchangeProperty("decision.dev-priority-focus.result").isEqualTo("missing-basics"))
                            .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                                    Focused missing-foundation response: communicate only when a concrete access/basic is actually
                                    unavailable; an empty source repo is still usable.
                                    """))
                            .to("tack:thought-enter?name=dev-missing-basics-focus&maxTokens=250")
                            .to("direct:dev-missing-basics-closeout")
                            .to("tack:thought-exit?name=dev-missing-basics-focus")
                        .when(exchangeProperty("decision.dev-priority-focus.result").isEqualTo("implementation"))
                            .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                                    Focused implementation unit: one assigned work:implementation child, task/product context, source or
                                    topology work, validation, PR state, and concise durable evidence.
                                    """))
                            .to("tack:thought-enter?name=dev-implementation-focus&maxTokens=450")
                            .to("direct:dev-implementation-focus")
                            .to("tack:thought-exit?name=dev-implementation-focus")
                        .when(exchangeProperty("decision.dev-priority-focus.result").isEqualTo("coordination"))
                            .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                                    Focused Developer coordination for one useful initiated technical/blocker/PR message or one fresh
                                    teammate exchange.
                                    """))
                            .to("tack:thought-enter?name=dev-coordination-focus&maxTokens=250")
                            .to("direct:dev-team-chat-sync")
                            .to("tack:thought-exit?name=dev-coordination-focus")
                        .when(exchangeProperty("decision.dev-priority-focus.result").isEqualTo("completion"))
                            .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                                    Focused Application Developer mission completion evaluation using minimal authoritative evidence.
                                    """))
                            .to("tack:thought-enter?name=dev-completion-focus&maxTokens=300")
                            .to("direct:dev-completion-focus")
                            .to("tack:thought-exit?name=dev-completion-focus")
                        .otherwise()
                            .delay(120000)
                    .end()
                .end()
                .end()
                .to("direct:dev-application-developer-root-exit");

        from("direct:dev-orientation")
                .routeId("dev-orientation")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read the smallest fresh current-mission slice needed to rank Developer focus. Determine only: whether shared
                        basics are accessible; one assigned work:implementation In Progress child and whether its linked PR is
                        open/merged/absent; whether newer concrete feedback/evidence requires more implementation; whether an assigned
                        work:implementation Todo child exists; one useful technical/blocker/PR message worth initiating or fresh exchange
                        needing attention; and completion plausibility. An accessible empty source repo counts as usable. Do not treat
                        missing main/default branch or untested push permission as a blocker. Prefer verified project/repo/work-item ids
                        already in thought rather than broad listing. Plane is authoritative for ownership/lifecycle/classification; Gitea
                        is authoritative for PR state. Report observations only. Do not recommend a next focus, preserve older HOLD/wait
                        conclusions against newer evidence, mutate, select, or implement work here.
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

        from("direct:dev-completion-focus")
                .routeId("dev-completion-focus")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Review current mission completion once using smallest authoritative reads and known ids. One unfinished
                        work:implementation child, topology investigation, open unmerged task PR, or product delivery outcome is enough to
                        conclude not complete. Plane/Gitea real state is authoritative; comments are evidence only. Do not mutate.
                        """))
                .to("tack:act?id=dev-mission-status-review&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Is the entire mission complete from application development? Answer yes only when durable current-mission evidence
                        shows no further work:implementation, topology investigation, validation, open task PR, or implementation
                        follow-up remains.
                        """))
                .to("tack:decide?id=application-developer-mission-complete");

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
                        Durable developer anchors: verified project/repo/chat ids, selected work:implementation child/Plane state, parent
                        acceptance, topology question when present, branch/commit/PR, validation evidence, blockers/feedback, and current
                        PR state.
                        """))
                .to("tack:thought-enter?name=application-developer-routine&maxTokens=500");

        from("direct:dev-application-developer-root-exit")
                .routeId("dev-application-developer-root-exit")
                .to("tack:thought-exit?name=application-developer-routine");

        from("direct:dev-project-basics-check")
                .routeId("dev-project-basics-check")
                .setProperty("decision.developer-has-project-basics.result", constant("no"))
                .setHeader(TackHeaders.PROMPT, constant("""
                        Verify developer project basics once without mutation: accessible Plane project, source repository,
                        docs/navigation, and team chat. Prefer verified ids already in thought. An existing accessible source repository
                        is usable even when it has zero commits/branches and no default branch; the first assigned Developer may
                        initialize it during implementation. Do not require product acceptance yet, a preexisting main branch, a PM chat
                        handoff, or speculative Gitea permission confirmation. One actual inaccessible basic is enough to stop.
                        """))
                .to("tack:act?id=dev-workspace-basics-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Are shared project basics accessible enough to select development work? Treat an existing accessible empty source
                        repository as valid. Do not require work/acceptance links, a default branch, commits, or pre-confirmed push
                        permission.
                        """))
                .to("tack:decide?id=developer-has-project-basics");

        from("direct:dev-missing-basics-closeout")
                .routeId("dev-missing-basics-closeout")
                .setHeader(TackHeaders.PROMPT, constant("""
                        A concrete project basic is actually inaccessible. Initiate or reply to one concise setup question only when it
                        would help PM repair that concrete access/basic sooner. An empty repository, absent main branch, or untested push
                        permission is NOT a missing-basic condition. Do not claim work only when a real required basic cannot be accessed.
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
                            Current developer work unit: one selected work:implementation child, real Plane state, parent acceptance,
                            topology question when applicable, source evidence, patch/PR, validation, and current feedback/blocker
                            evidence.
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
                        Read only recent project chat needed for one useful technical exchange. Do not require an existing conversation
                        before posting. Initiate one concise message when a new/updated PR, an ACTUAL implementation/access failure,
                        technical finding, validation limitation, or named question would help PM/PO/Director act sooner; otherwise answer
                        one fresh technical question. Do not ask PM to re-announce an assignment already represented by Plane Todo +
                        assignee. Link authoritative Plane/Gitea evidence when useful. Do not volunteer for unassigned work or repeat
                        generic status.
                        """))
                .to("tack:act?id=dev-team-chat-sync&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:dev-work-selection")
                .routeId("dev-work-selection")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Task selection only: one assigned work:implementation child, never work:product. Prefer actionable In Progress
                        work; otherwise assigned Todo when no current source-delivery child owns an open unmerged PR. Todo + assignment is
                        complete handoff; Developer claims Todo -> In Progress.
                        """))
                .to("tack:thought-enter?name=dev-selection&maxTokens=250")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Using fresh Plane evidence, inspect only items carrying work:implementation and assigned to you. Never select
                        work:product. If labels were not returned, read classification instead of guessing from wording.

                        First consider assigned In Progress work:implementation children. Continue one when implementation/topology
                        investigation is underway or newer concrete teammate feedback/evidence requires another pass. For normal source
                        delivery, inspect the linked Gitea PR only as needed: if the task's PR is OPEN and no newer evidence requires
                        changes, the task is waiting for Director integration and is not actionable source work. Comments do not create
                        review/integration states.

                        If any assigned normal source-delivery child is In Progress with an OPEN unmerged PR, do not claim a different
                        Todo source child. Keep unmerged source WIP to one integration unit. If its PR is merged, PM should reconcile the
                        Plane child to Done; do not continue source changes merely because Plane has not caught up.

                        If no actionable In Progress child exists and no assigned source child has an open unmerged PR, select one
                        assigned Todo work:implementation child. Todo + assignment is the authoritative PM -> DEV handoff. Do NOT wait for
                        PM to repeat the handoff in Mattermost or move it In Progress. Move exactly that selected child Todo -> In
                        Progress yourself. A topology investigation may be selected when explicitly described as evidence-gathering and
                        dependency-free with an exact question.

                        Ignore Backlog, Done, Cancelled, unassigned work, work assigned to others, and work:product parents.
                        """))
                .to("tack:act?id=dev-work-selection&toolProfile=" + ProjectManagementToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Is one assigned actionable work:implementation child now selected In Progress? Answer yes for active normal
                        implementation/recovery or explicit topology investigation. Answer no when the only In Progress source child has
                        an open unmerged PR with no newer change required, or no eligible assigned Todo work:implementation item exists.
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
                            Current implementation step: selected work:implementation child, task type, parent acceptance behavior, source
                            evidence, branch/PR, validation, current teammate feedback, blocker evidence, and technical finding when
                            applicable.
                            """))
                    .to("tack:thought-enter?name=dev-implementation&maxTokens=250")
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Read only the selected In Progress work:implementation child. Verify Developer assignment, work:implementation
                            classification, parent work:product acceptance reference, repo, dependencies, expected validation evidence,
                            and any newer concrete feedback/blocker context. For topology investigation verify dependency-free exact
                            technical question. A repository may be empty; no branch/main is required in task context. Do not mutate. If
                            required delivery context is malformed/missing, identify exact PM-owned gap and stop reading.
                            """))
                    .to("tack:act?id=dev-task-context-read&toolProfile=" + ProjectManagementToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Is selected work:implementation context valid enough to perform assigned work? Normal delivery requires
                            assignment, work:implementation classification, parent acceptance, repository locator, dependency state, and
                            expected validation evidence. It does NOT require an existing branch/main, PM chat confirmation, or
                            pre-confirmed push permission. Topology investigation requires assignment, repository, dependency-free state,
                            affected work ids, acceptance refs, exact technical question. Answer no on genuinely missing/contradictory
                            required context.
                            """))
                    .to("tack:decide?id=developer-has-valid-task-context")
                    .choice()
                        .when(exchangeProperty("decision.developer-has-valid-task-context.result").isEqualTo("yes"))
                            .setHeader(TackHeaders.PROMPT, constant("""
                                    Is this selected implementation child explicitly a topology investigation rather than normal source
                                    delivery? Answer yes only when current task description clearly asks for evidence gathering and an
                                    exact technical sequencing/integration question.
                                    """))
                            .to("tack:decide?id=developer-is-topology-investigation")
                            .choice()
                                .when(exchangeProperty("decision.developer-is-topology-investigation.result").isEqualTo("yes"))
                                    .setHeader(TackHeaders.PROMPT, constant("""
                                            Investigate only source/docs/affected Plane work needed to answer the exact topology question.
                                            Trace referenced dependencies and source boundaries. Do not change source merely to create
                                            progress. Stop when requested facts are established or remaining uncertainty is clearly
                                            product-owned.
                                            """))
                                    .to("tack:act?id=dev-topology-investigation&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                                    .setHeader(TackHeaders.PROMPT, constant("""
                                            Update only the selected topology-investigation child. Keep it In Progress and add/refresh one
                                            concise evidence comment containing affected work ids, observed source/contract facts,
                                            evidence-backed dependency/sequencing recommendation, and residual product question if any. Do
                                            not encode a named TECHNICAL FINDING workflow state; the comment is evidence/context. Avoid
                                            duplicate equivalent evidence.
                                            """))
                                    .to("tack:act?id=dev-topology-finding-update&toolProfile=" + ProjectManagementToolProfile.ID)
                                    .choice()
                                        .when(exchangeProperty("act.dev-topology-finding-update.durableProgress").isEqualTo(true))
                                            .setProperty("devImplementationMadeProgress", constant(true))
                                            .setProperty("devImplementationStepProgress", constant(true))
                                    .end().endChoice()
                                .otherwise()
                                    .setProperty("devSourceCloseoutAllowed", constant(true))
                                    .setHeader(TackHeaders.PROMPT, constant("""
                                            Read only direct product-acceptance links and existing source needed for this child. Identify
                                            one coherent next patch and validation target. An empty repository is valid: the first
                                            implementation patch may establish the initial project structure. If newer concrete review
                                            feedback requires source refresh, inspect the existing task branch/PR against origin/main when
                                            those exist. Stop when patch is clear.
                                            """))
                                    .to("tack:act?id=dev-source-context-plan&toolProfile=" + SourceControlToolProfile.ID)
                                    .setHeader(TackHeaders.PROMPT, constant("""
                                        Apply exactly one coherent source-control patch for the selected implementation child. For an
                                        empty repository, initialize the repository in the sandbox, establish the minimal baseline/default
                                        branch needed for normal Git operation, then create/use the task branch and continue the assigned
                                        implementation; do not wait for PM to seed main. For normal implementation use/create the task
                                        branch, validate changed behavior when practical, commit, push, and create/update one PR; avoid
                                        duplicate PRs. Do not preflight hypothetical collaborator permission. Attempt the ordinary push/PR
                                        workflow; only a real auth/authorization rejection is a blocker. For evidence-backed
                                        source-refresh feedback, recover the same task branch against current origin/main when present,
                                        rerun relevant validation, and reuse the same open PR when possible. If resolution requires
                                        incompatible product behavior, do not guess; leave concise blocker evidence for Product Owner/PM.
                                        Do not create replacement tasks or branches merely to advance workflow.
                                        """))
                                    .to("tack:act?id=dev-delivery-step&toolProfile=" + SourceControlWriteToolProfile.ID)
                                    .choice()
                                        .when(exchangeProperty("act.dev-delivery-step.durableProgress").isEqualTo(true))
                                            .setProperty("devImplementationMadeProgress", constant(true))
                                            .setProperty("devImplementationStepProgress", constant(true))
                                    .end()
                                    .setHeader(TackHeaders.PROMPT, constant("""
                                            Check fresh validation/reviewability evidence: changed files, current branch/head, exact PR
                                            state, tests/validation actually run, and limitations. Do not claim validation that did not
                                            occur.
                                            """))
                                    .to("tack:act?id=dev-validation-step&toolProfile=" + SourceControlToolProfile.ID)
                                    .setHeader(TackHeaders.PROMPT, constant("""
                                        Update only the selected work:implementation child in Plane. Keep it In Progress while its PR is
                                        open/unmerged. Add or refresh one concise evidence/context comment only when useful, linking
                                        current branch/head/PR, changed files, validation, parent item, acceptance ref, or exact blocker.
                                        Do NOT create REVIEW READY, INTEGRATION READY, or other named comment workflow states. The open PR
                                        itself is the integration signal for Director. For blockers, name only a real observed missing
                                        product judgment, malformed delivery context, failed source/auth operation, technical evidence, or
                                        external condition and who can resolve it. Avoid duplicate equivalent comments. Do not move to
                                        Done; PM reconciles Done after verified merge.
                                        """))
                                    .to("tack:act?id=dev-plane-status-evidence-update&toolProfile=" + ProjectManagementToolProfile.ID)
                                    .choice()
                                        .when(exchangeProperty("act.dev-plane-status-evidence-update.durableProgress").isEqualTo(true))
                                            .setProperty("devImplementationMadeProgress", constant(true))
                                            .setProperty("devImplementationStepProgress", constant(true))
                                    .end()
                            .end().endChoice()
                        .otherwise()
                            .setProperty("devSourceCloseoutAllowed", constant(false))
                            .setHeader(TackHeaders.PROMPT, constant("""
                                    Do not touch source. Keep selected child In Progress and add/refresh one concise evidence comment
                                    naming the genuinely missing/malformed PM-owned delivery context and required repair. Do not use
                                    absent main/default branch, untested source permission, or missing Mattermost confirmation as
                                    blockers. Do not encode a named BLOCKED workflow state in comments.
                                    """))
                            .to("tack:act?id=dev-invalid-context-blocked&toolProfile=" + ProjectManagementToolProfile.ID)
                            .choice()
                                .when(exchangeProperty("act.dev-invalid-context-blocked.durableProgress").isEqualTo(true))
                                    .setProperty("devImplementationMadeProgress", constant(true))
                                    .setProperty("devImplementationStepProgress", constant(true))
                            .end()
                    .end()
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Is this implementation step complete for now because normal source delivery has an open reviewable PR with
                            current validation evidence, topology investigation has recorded the requested evidence, or a concrete
                            observed blocker has been durably recorded? Answer no only when an obvious next
                            production-code/test/recovery/investigation step is still required before that natural stop.
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
                        Make one focused documentation improvement only when it preserves durable engineering knowledge for this valid
                        implementation work. Do not create activity logs.
                        """))
                .to("tack:act?id=dev-documentation-closeout&toolProfile=" + SourceControlWriteToolProfile.ID)
                .to("direct:dev-work-closeout-no-source");

        from("direct:dev-work-closeout-no-source")
                .routeId("dev-work-closeout-no-source")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Initiate or reply to one concise project-chat message when a new/updated PR, real blocker, topology evidence,
                        validation limitation, teammate feedback, or direct question would help another role act sooner. Do not require an
                        existing thread. Avoid generic status. This closeout has no source-write capability.
                        """))
                .to("tack:act?id=dev-team-chat-closeout&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:dev-no-work-closeout")
                .routeId("dev-no-work-closeout")
                .setHeader(TackHeaders.PROMPT, constant("""
                        If no assigned actionable work:implementation In Progress/Todo child exists, send one concise message only when PM
                        would benefit from knowing the executable queue is empty or actual access is blocking work. Do not ask PM to
                        re-announce an assignment already represented in Plane. Do not browse, shape, implement, or self-assign product
                        backlog work. Avoid repeating the same no-work message.
                        """))
                .to("tack:act?id=dev-no-work-note&toolProfile=" + TeamChatToolProfile.ID);
    }
}
