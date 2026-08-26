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
                        investigate explicitly assigned delivery-topology questions when needed, validate work, create or update
                        reviewable pull requests, and leave durable REVIEW READY, TECHNICAL FINDING, or BLOCKED evidence for
                        Project Manager follow-up. Product parents are never developer tasks.
                        """, "direct:example-software-team-application-developer")
                .subroutine(
                        "work-selection",
                        "Find or start one assigned implementation child using Plane Todo/In Progress state without changing source files.",
                        "direct:example-software-team-application-developer-work-selection")
                .subroutine(
                        "implementation",
                        "Advance one assigned implementation child through source changes or topology investigation, validation, and a durable REVIEW READY, TECHNICAL FINDING, or BLOCKED handoff.",
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
                                    One unfinished implementation child, topology investigation, or product delivery outcome is enough
                                    to conclude not complete. Plane state is authoritative. Superseded historical handoffs are not
                                    current work. Do not mutate or begin new development in this check.
                                    """))
                    .to("tack:act?id=dev-mission-status-review&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is the entire mission complete from the application-development perspective?
                                    Return true only when durable current-mission evidence shows no further development,
                                    topology investigation, validation, review response, or implementation follow-up remains.
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
                        source repository, topology question when present, branch/commit/pull request, validation evidence,
                        technical findings, and current review disposition.
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
                    .setProperty("devSourceCloseoutAllowed", constant(false))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Current developer work unit: one freshly selected assigned implementation child, its real Plane state,
                        parent acceptance, topology question when applicable, current review disposition, source evidence,
                        patch/PR when applicable, and validation.
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
                            .choice()
                                .when(exchangeProperty("devSourceCloseoutAllowed").isEqualTo(true))
                                    .to("direct:dev-work-closeout")
                                .otherwise()
                                    .to("direct:dev-work-closeout-no-source")
                            .endChoice()
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
                        In Progress work, then assigned Todo work. Respect current review/integration/finding wait handoffs.
                        """))
                .to("tack:thought-enter?name=dev-selection&maxTokens=250")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Using fresh Plane evidence, inspect only IMPLEMENTATION CHILDREN assigned to you.

                                First consider assigned In Progress implementation children. Continue one when implementation
                                or an explicit TOPOLOGY INVESTIGATION is actively underway, or when a newer PM REVIEW FEEDBACK
                                requests another pass. A newer REVIEW FEEDBACK beginning SOURCE_REFRESH_REQUIRED is actionable
                                recovery work. CONTEXT_REPAIRED and PRODUCT_QUESTION_RESOLVED are also actionable and require
                                fresh task/product-context validation. REVIEW FEEDBACK beginning TECHNICAL_FINDING_INCOMPLETE is
                                actionable only for the same TOPOLOGY INVESTIGATION and asks for a more precise finding.

                                Do not make additional changes to an item simply waiting on current REVIEW READY, INTEGRATION READY,
                                or TECHNICAL FINDING with no newer PM disposition.

                                If no actionable assigned In Progress child exists, consider one assigned Todo implementation child.
                                Todo plus assignment is the authoritative PM -> DEV handoff. A description beginning TOPOLOGY INVESTIGATION
                                is an executable evidence-gathering child, not a normal code-delivery task: it must be dependency-free,
                                name affected work-item ids, acceptance/source context, and an exact technical question. If selected,
                                move exactly that child Todo -> In Progress.

                                Ignore Backlog, Done, Cancelled, unassigned work, work assigned to others, and product parents.
                                Never implement a product parent directly.
                                """))
                .to("tack:act?id=dev-work-selection&toolProfile=" + ProjectManagementToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                                Is one assigned actionable implementation child now selected in In Progress?
                                Return true for normal implementation, an active TOPOLOGY INVESTIGATION, or a child with newer PM
                                REVIEW FEEDBACK such as SOURCE_REFRESH_REQUIRED, CONTEXT_REPAIRED, PRODUCT_QUESTION_RESOLVED,
                                or TECHNICAL_FINDING_INCOMPLETE. Return false when every assigned In Progress child is waiting on
                                review/integration/finding disposition and no assigned Todo child or newer actionable PM feedback exists.
                                """))
                .to("tack:decide?id=developer-has-work")
                .to("tack:thought-exit?name=dev-selection");

        from("direct:dev-implementation-loop")
                .routeId("dev-implementation-loop")
                .setProperty("developerImplementationCycle", constant(0))
                .setProperty("devImplementationMadeProgress", constant(false))
                .setProperty("devImplementationStepProgress", constant(true))
                .setProperty("decision.developer-implementation-complete.result", constant(false))
                .loopDoWhile(simple("${exchangeProperty.devImplementationStepProgress} == true && ${exchangeProperty.developerImplementationCycle} < 6"))
                    .setProperty("developerImplementationCycle", simple("${exchangeProperty.developerImplementationCycle++}"))
                    .setProperty("devImplementationStepProgress", constant(false))
                    .setProperty("decision.developer-has-valid-task-context.result", constant(false))
                    .setProperty("decision.developer-is-topology-investigation.result", constant(false))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Current implementation step: selected child, task type, parent acceptance behavior, source evidence,
                        branch/PR when applicable, validation, current PM feedback, and technical finding when applicable.
                        """))
                    .to("tack:thought-enter?name=dev-implementation&maxTokens=250")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Read only the selected In Progress implementation child using fresh Plane evidence. Verify
                                    assignment, parent acceptance reference, repository, dependencies, expected durable handoff,
                                    and newest PM REVIEW FEEDBACK. For PRODUCT_QUESTION_RESOLVED, reread clarified parent acceptance.
                                    For TOPOLOGY INVESTIGATION, verify it is dependency-free and names affected work-item ids plus
                                    one exact technical sequencing/integration question. Do not mutate in this read. If required
                                    delivery context is malformed or missing, identify the exact PM-owned gap and stop reading.
                                    """))
                    .to("tack:act?id=dev-task-context-read&toolProfile=" + ProjectManagementToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is the selected implementation child's context valid enough to perform its assigned work?
                                    For normal delivery require assignment, parent acceptance, repository, dependency state, and
                                    expected review evidence. For TOPOLOGY INVESTIGATION require assignment, repository, dependency-free
                                    state, affected work-item ids, acceptance references, and exact technical question. Return false
                                    when required context is missing, contradictory, or malformed.
                                    """))
                    .to("tack:decide?id=developer-has-valid-task-context")
                    .choice()
                        .when(exchangeProperty("decision.developer-has-valid-task-context.result").isEqualTo(true))
                            .setHeader(TackHeaders.PROMPT, constant("""
                                            Is this selected implementation child explicitly a TOPOLOGY INVESTIGATION?
                                            Return true only when its current description begins TOPOLOGY INVESTIGATION and its
                                            expected durable outcome is TECHNICAL FINDING rather than REVIEW READY.
                                            """))
                            .to("tack:decide?id=developer-is-topology-investigation")
                            .choice()
                                .when(exchangeProperty("decision.developer-is-topology-investigation.result").isEqualTo(true))
                                    .setHeader(TackHeaders.PROMPT, constant("""
                                                    Investigate only the source/docs and affected Plane work needed to answer this
                                                    topology question. Trace the referenced dependencies and inspect source boundaries,
                                                    interfaces, existing implementation ordering, and accepted behavior. Do not change
                                                    source code merely to create progress. Stop when the requested sequencing/dependency
                                                    facts are established or the remaining uncertainty is clearly product-owned.
                                                    """))
                                    .to("tack:act?id=dev-topology-investigation&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                                    .setHeader(TackHeaders.PROMPT, constant("""
                                                    Update exactly the selected TOPOLOGY INVESTIGATION child in Plane. Keep it In Progress
                                                    and add or refresh one comment beginning TECHNICAL FINDING. Include affected work-item
                                                    ids, observed source/contract facts, the evidence-backed recommended dependency or
                                                    sequencing correction, and any residual product question. If evidence cannot establish
                                                    a safe technical recommendation, say exactly what product judgment is required rather
                                                    than guessing. TECHNICAL FINDING is the durable Developer -> PM handoff; no PR or REVIEW
                                                    READY is required for this investigation. Avoid duplicate equivalent findings.
                                                    """))
                                    .to("tack:act?id=dev-topology-finding-update&toolProfile=" + ProjectManagementToolProfile.ID)
                                    .choice()
                                        .when(exchangeProperty("act.dev-topology-finding-update.durableProgress").isEqualTo(true))
                                            .setProperty("devImplementationMadeProgress", constant(true))
                                            .setProperty("devImplementationStepProgress", constant(true))
                                    .endChoice()
                                .otherwise()
                                    .setProperty("devSourceCloseoutAllowed", constant(true))
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
                                                    fetch current origin/main; keep the same task branch; merge current origin/main without
                                                    rewriting shared history; resolve only evidence-supported conflicts; rerun relevant validation;
                                                    and push the same branch. Reuse the same PR when it is open. If the prior PR is closed-unmerged,
                                                    reopen it when the available source-control tool supports that operation; otherwise create one new
                                                    PR for the SAME existing task branch only after confirming no usable open PR exists. This is PR
                                                    recovery, not a replacement task/branch. Publish fresh REVIEW READY for the resulting current PR/head.
                                                    If resolution requires choosing between incompatible product behavior, do not guess. Record BLOCKED
                                                    owned by Product Owner so PM can route PRODUCT QUESTION. For technical coordination not answerable
                                                    from current evidence, record BLOCKED owned by Project Management and state the missing technical
                                                    evidence. Do not invent Director/Architect/Infrastructure as workflow owners in this routine set.

                                                    Incorporate all current PM REVIEW FEEDBACK, including clarified product decisions from
                                                    PRODUCT_QUESTION_RESOLVED. Do not create replacement tasks or branches.
                                                    """))
                                    .to("tack:act?id=dev-delivery-step&toolProfile=" + SourceControlWriteToolProfile.ID)
                                    .choice()
                                        .when(exchangeProperty("act.dev-delivery-step.durableProgress").isEqualTo(true))
                                            .setProperty("devImplementationMadeProgress", constant(true))
                                            .setProperty("devImplementationStepProgress", constant(true))
                                    .endChoice()
                                    .setHeader(TackHeaders.PROMPT, constant("""
                                                    Check fresh validation and reviewability evidence: changed files, current branch/head,
                                                    pull request state, tests or other validation actually run, and any limitation.
                                                    Do not claim validation that did not occur.
                                                    """))
                                    .to("tack:act?id=dev-validation-step&toolProfile=" + SourceControlToolProfile.ID)
                                    .setHeader(TackHeaders.PROMPT, constant("""
                                                    Update exactly the selected implementation child in Plane.

                                                    Keep it In Progress. When ready for PM review, add or refresh one comment beginning REVIEW READY
                                                    with current branch, commit/head, existing PR, changed files, validation, parent product item,
                                                    and canonical acceptance reference. A source-refresh recovery must publish fresh REVIEW READY.
                                                    REVIEW READY is a handoff comment, not a Plane state. Do not move to Done.

                                                    When blocked by implementation evidence, keep the truthful real state and add/refresh one BLOCKED
                                                    comment. Use Product Owner only for unresolved product intent/acceptance judgment. Use Project
                                                    Management for malformed delivery context, technical coordination, or external/system dependencies;
                                                    name the actual external condition and evidence needed inside the comment. These are the routable
                                                    blocker owners in the current routine set. Avoid duplicate equivalent handoff comments.
                                                    """))
                                    .to("tack:act?id=dev-plane-status-evidence-update&toolProfile=" + ProjectManagementToolProfile.ID)
                                    .choice()
                                        .when(exchangeProperty("act.dev-plane-status-evidence-update.durableProgress").isEqualTo(true))
                                            .setProperty("devImplementationMadeProgress", constant(true))
                                            .setProperty("devImplementationStepProgress", constant(true))
                                    .endChoice()
                            .endChoice()
                        .otherwise()
                            .setProperty("devSourceCloseoutAllowed", constant(false))
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
                                    .setProperty("devImplementationStepProgress", constant(true))
                            .endChoice()
                    .end()
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is this implementation step complete for now because current REVIEW READY evidence exists,
                                    current TECHNICAL FINDING exists for a TOPOLOGY INVESTIGATION, or the task is durably BLOCKED?
                                    Return false only when there is an obvious next production-code/test/recovery/investigation step
                                    required before the appropriate handoff.
                                    """))
                    .to("tack:decide?id=developer-implementation-complete")
                    .choice()
                        .when(exchangeProperty("decision.developer-implementation-complete.result").isEqualTo(true))
                            .setProperty("devImplementationStepProgress", constant(false))
                    .end()
                    .to("tack:thought-exit?name=dev-implementation")
                .end();

        from("direct:dev-work-closeout")
                .routeId("dev-work-closeout")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Make one focused documentation improvement only when it preserves durable engineering knowledge
                                for this VALID normal implementation work. Do not create activity logs.
                                """))
                .to("tack:act?id=dev-documentation-closeout&toolProfile=" + SourceControlWriteToolProfile.ID)
                .to("direct:dev-work-closeout-no-source");

        from("direct:dev-work-closeout-no-source")
                .routeId("dev-work-closeout-no-source")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Reply in project chat only when review evidence, a blocker, topology finding, PM feedback, or a
                                teammate's question needs interaction. Avoid generic status. This closeout has no source-write capability.
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
