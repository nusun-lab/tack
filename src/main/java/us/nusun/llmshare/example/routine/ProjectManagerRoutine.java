package us.nusun.llmshare.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.example.tool.ProjectManagementToolProfile;
import us.nusun.llmshare.example.tool.SourceControlToolProfile;
import us.nusun.llmshare.example.tool.SourceControlWriteToolProfile;
import us.nusun.llmshare.example.tool.TeamChatToolProfile;
import us.nusun.llmshare.example.tool.WorkspaceCoordinationToolProfile;
import us.nusun.llmshare.example.tool.WorkspaceReadonlyToolProfile;
import us.nusun.llmshare.framework.inventory.RoutineProvider;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.tack.TackHeaders;

import java.util.List;

@ApplicationScoped
public class ProjectManagerRoutine extends RouteBuilder implements RoutineProvider {
    public static final String ID = "software-team/project-manager";

    @Override
    public List<RoutineDefinition> routines() {
        return List.of(routine());
    }

    public RoutineDefinition routine() {
        return RoutineDefinition.builder(ID)
                .name("Project Manager")
                .main("""
                        Own project management for the whole mission through completion. Establish and maintain the shared
                        delivery workspace, consume Todo product slices from Product Ownership, decompose each product slice
                        into discrete implementation children, assign and sequence those children, coordinate review and
                        integration, and return completed In Progress product parents to Product Ownership for acceptance.
                        Product slices are user outcomes; implementation children are independently finishable technical outcomes.
                        """, "direct:example-software-team-project-manager")
                .subroutine(
                        "bootstrap",
                        "Establish only the shared Plane, source-control, documentation, team-chat, access, and navigation foundation required for delivery.",
                        "direct:example-software-team-project-manager-bootstrap")
                .subroutine(
                        "coordination",
                        "Operate an established delivery workspace: decompose Todo product parents, coordinate implementation children, review, integration, blockers, and product acceptance.",
                        "direct:example-software-team-project-manager-coordination")
                .subroutine(
                        "task-planning",
                        "Decompose one Todo or active product parent into discrete implementation children whose combined coverage satisfies the product acceptance.",
                        "direct:example-software-team-project-manager-task-planning")
                .defaultLane("high-pass")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-software-team-project-manager")
                .routeId("example-software-team-project-manager")
                .to("direct:pm-project-manager-root-enter")
                .setProperty("decision.project-manager-mission-complete.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.project-manager-mission-complete.result").isNotEqualTo(true))
                    .to("direct:pm-project-basics-check")
                    .choice()
                        .when(exchangeProperty("decision.collaboration-setup-complete.result").isNotEqualTo(true))
                            .to("direct:pm-project-bootstrap")
                    .end()
                    .to("direct:pm-coordination-loop")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Review current mission completion status once using the smallest authoritative read.
                                    One unfinished product parent, implementation child, blocker, review, merge, or acceptance
                                    follow-up is enough to conclude not complete. Plane state is authoritative. Do not mutate.
                                    """))
                    .to("tack:act?id=pm-mission-status-review&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is the entire mission complete from the project-management perspective?
                                    Return true only when all required product parents are Done and no implementation,
                                    review, integration, blocker, assignment, or delivery follow-up remains.
                                    """))
                    .to("tack:decide?id=project-manager-mission-complete")
                    .filter(exchangeProperty("decision.project-manager-mission-complete.result").isNotEqualTo(true))
                        .delay(120000)
                    .end()
                .end()
                .end()
                .to("direct:pm-project-manager-root-exit");

        from("direct:example-software-team-project-manager-bootstrap")
                .routeId("example-software-team-project-manager-bootstrap")
                .to("direct:pm-project-manager-root-enter")
                .to("direct:pm-project-basics-check")
                .choice()
                    .when(exchangeProperty("decision.collaboration-setup-complete.result").isNotEqualTo(true))
                        .to("direct:pm-project-bootstrap")
                .end()
                .to("direct:pm-project-manager-root-exit");

        from("direct:example-software-team-project-manager-coordination")
                .routeId("example-software-team-project-manager-coordination")
                .to("direct:pm-project-manager-root-enter")
                .to("direct:pm-project-basics-check")
                .choice()
                    .when(exchangeProperty("decision.collaboration-setup-complete.result").isEqualTo(true))
                        .to("direct:pm-coordination-loop")
                .end()
                .to("direct:pm-project-manager-root-exit");

        from("direct:example-software-team-project-manager-task-planning")
                .routeId("example-software-team-project-manager-task-planning")
                .to("direct:pm-project-manager-root-enter")
                .to("direct:pm-project-basics-check")
                .choice()
                    .when(exchangeProperty("decision.collaboration-setup-complete.result").isEqualTo(true))
                        .to("direct:pm-work-intake")
                        .choice()
                            .when(exchangeProperty("decision.pm-has-product-for-delivery.result").isEqualTo(true))
                                .to("direct:pm-task-planning-loop")
                        .end()
                .end()
                .to("direct:pm-project-manager-root-exit");

        from("direct:pm-project-manager-root-enter")
                .routeId("pm-project-manager-root-enter")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Durable delivery anchors: project basics, Plane workspace, selected product parent,
                        implementation-child coverage, source repository, review/integration evidence, and team chat.
                        """))
                .to("tack:thought-enter?name=project-manager-routine&maxTokens=500");

        from("direct:pm-project-manager-root-exit")
                .routeId("pm-project-manager-root-exit")
                .to("tack:thought-exit?name=project-manager-routine");

        from("direct:pm-project-basics-check")
                .routeId("pm-project-basics-check")
                .setProperty("decision.collaboration-setup-complete.result", constant(false))
                .setHeader(TackHeaders.PROMPT, constant("""
                                Verify project basics once without mutation. Confirm exactly one current-mission Plane
                                workspace, documentation repository/navigation, source repository under agent-workspace,
                                team chat, and team access. Stop when one required basic is clearly missing.
                                """))
                .to("tack:act?id=pm-workspace-basics-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                                Are project basics verified and current enough for delivery coordination?
                                """))
                .to("tack:decide?id=collaboration-setup-complete");

        from("direct:pm-project-bootstrap")
                .routeId("pm-project-bootstrap")
                .setProperty("collaborationSetupAttempt", constant(0))
                .loopDoWhile(exchangeProperty("decision.collaboration-setup-complete.result").isNotEqualTo(true))
                    .setProperty("collaborationSetupAttempt", simple("${exchangeProperty.collaborationSetupAttempt++}"))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Bootstrap only: Plane workspace, team chat, membership, source/documentation repositories,
                        and navigation. Product backlog items and implementation tasks are not bootstrap artifacts.
                        """))
                    .to("tack:thought-enter?name=pm-bootstrap&maxTokens=300")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Create or confirm exactly one shared current-mission Plane project. Preserve an existing
                                    useful project. Do not create product or implementation work during bootstrap.
                                    """))
                    .to("tack:act?id=plane-project-provisioning&toolProfile=" + WorkspaceCoordinationToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Create or confirm exactly one markdown documentation repository under agent-workspace.
                                    Do not create project-coordination issues in source control.
                                    """))
                    .to("tack:act?id=documentation-repository-provisioning&toolProfile=" + WorkspaceCoordinationToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Create or confirm exactly one target source repository under agent-workspace.
                                    """))
                    .to("tack:act?id=source-repository-provisioning&toolProfile=" + WorkspaceCoordinationToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Create or confirm one private current-mission team-chat channel and keep current team
                                    membership usable. Never create a duplicate channel.
                                    """))
                    .to("tack:act?id=chat-channel-setup&toolProfile=" + TeamChatToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Create or update compact canonical documentation navigation and project-basics pages.
                                    Include mission, agent-workspace repositories, Plane project, team chat, and role responsibilities.
                                    """))
                    .to("tack:act?id=documentation-project-index&toolProfile=" + SourceControlWriteToolProfile.ID)
                    .to("direct:pm-project-basics-check")
                    .to("tack:thought-exit?name=pm-bootstrap")
                .end();

        from("direct:pm-coordination-loop")
                .routeId("pm-coordination-loop")
                .to("direct:pm-team-onboarding")
                .setProperty("pmProductiveIterations", constant(0))
                .setProperty("pmMadeProgress", constant(true))
                .loopDoWhile(simple("${exchangeProperty.pmMadeProgress} == true && ${exchangeProperty.pmProductiveIterations} < 10"))
                    .setProperty("pmProductiveIterations", simple("${exchangeProperty.pmProductiveIterations++}"))
                    .setProperty("pmMadeProgress", constant(false))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Current delivery pass: one fresh active implementation child needing review/integration/recovery/promotion,
                        or one Todo/active product parent needing second-stage delivery decomposition.
                        """))
                    .to("tack:thought-enter?name=pm-cycle&maxTokens=300")
                    .to("direct:pm-active-delivery-check")
                    .choice()
                        .when(exchangeProperty("act.pm-active-delivery-update.durableProgress").isEqualTo(true))
                            .setProperty("pmMadeProgress", constant(true))
                    .end()
                    .to("direct:pm-work-intake")
                    .choice()
                        .when(exchangeProperty("decision.pm-has-product-for-delivery.result").isEqualTo(true))
                            .to("direct:pm-task-planning-loop")
                            .choice()
                                .when(exchangeProperty("pmTaskPlanningMadeProgress").isEqualTo(true))
                                    .setProperty("pmMadeProgress", constant(true))
                            .end()
                    .end()
                    .to("tack:thought-exit?name=pm-cycle")
                .end()
                .to("direct:pm-team-sync");

        from("direct:pm-team-onboarding")
                .routeId("pm-team-onboarding")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Keep current mission teammates present in Plane and team chat where supported. Add missing
                                teammates; remove nobody unless mission evidence clearly says they left. Do not let membership
                                housekeeping obscure delivery work.
                                """))
                .to("tack:act?id=pm-team-onboarding&toolProfile=" + WorkspaceCoordinationToolProfile.ID);

        from("direct:pm-active-delivery-check")
                .routeId("pm-active-delivery-check")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        One delivery-maintenance item: In Progress implementation child awaiting review, merge verification,
                        stale/conflicted integration recovery, blocked coordination, dependency promotion, or completed-parent handoff.
                        """))
                .to("tack:thought-enter?name=pm-active-delivery&maxTokens=300")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Read at most one highest-value current delivery item using fresh Plane and linked source evidence.

                                Priority:
                                1. In Progress implementation child with current REVIEW READY and no later PM disposition:
                                   inspect its acceptance reference, current diff/head, changed files, validation, linked PR,
                                   and current PR review/comment activity. REVIEW READY work outranks a clean INTEGRATION READY
                                   item that is merely waiting for the Director, so one approved PR cannot hide later reviews.
                                2. In Progress implementation child with INTEGRATION READY when its exact linked PR may have
                                   merged or the approval may have become stale because the PR conflicts/is not mergeable,
                                   is closed without merge, its head/base changed, or newer review/comment activity changed the
                                   review surface. If the IR is still clean, open, unchanged, and simply waiting for Director
                                   merge, leave it alone when any other actionable PM item exists.
                                3. Todo/In Progress implementation child with a current BLOCKED comment needing coordination.
                                   When the blocker names Project Management as owner because delivery context is missing or
                                   malformed, read the parent acceptance and canonical repository/work-item evidence needed to
                                   repair that exact child in place.
                                4. Backlog implementation child whose declared dependencies may now all be Done.
                                5. In Progress product parent whose implementation children may all be Done. Read the parent's
                                   PRODUCT ACCEPTANCE READY and PRODUCT ACCEPTANCE FEEDBACK comment ordering as part of this check.
                                   Unresolved feedback is newer PRODUCT ACCEPTANCE FEEDBACK for which no later remediation work
                                   has been completed and no later valid PRODUCT ACCEPTANCE READY has superseded it.

                                REVIEW READY, INTEGRATION READY, REVIEW FEEDBACK, BLOCKED, PRODUCT ACCEPTANCE READY, and
                                PRODUCT ACCEPTANCE FEEDBACK are handoff/evidence comments, never Plane states. Do not mutate in this read.
                                """))
                .to("tack:act?id=pm-active-delivery-evidence-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                                Update at most the delivery item already read.

                                - If an INTEGRATION READY implementation child's exact reviewed PR/head is verified merged,
                                  move that implementation child In Progress -> Done.
                                - If an INTEGRATION READY approval is stale because the PR is conflicted/not mergeable, closed
                                  without merge, the head/base changed, or newer material review/comment activity changed the
                                  review surface, keep the child In Progress and add/refresh a newer REVIEW FEEDBACK comment.
                                  For source drift or conflicts, begin the feedback with SOURCE_REFRESH_REQUIRED and include
                                  repository, PR, current head, expected base, invalidation reason, and the required action:
                                  update the existing task branch from current main, resolve only unambiguous conflicts, rerun
                                  validation, push the same branch/PR, and publish fresh REVIEW READY. Never create a replacement
                                  child or PR. For a closed-unmerged PR, name the precise source-control recovery required rather
                                  than pretending integration is still pending.
                                - If a REVIEW READY implementation child is acceptable against its referenced product acceptance,
                                  current diff/review surface, and available validation, keep it In Progress and add/refresh one
                                  INTEGRATION READY comment containing repository, PR, reviewedHead, reviewedBase, reviewedAt,
                                  validation, and acceptance reference. The Director owns the merge.
                                - If review finds a defect, unmet acceptance behavior, or meaningful unresolved PR feedback,
                                  keep the task In Progress and add one precise REVIEW FEEDBACK comment.
                                - If a Backlog implementation child's dependencies are all Done and the task is otherwise
                                  actionable and assigned, move it Backlog -> Todo.
                                - If a current BLOCKED comment names Project Management as owner because required delivery
                                  context is missing or malformed, repair that exact implementation child in place using fresh
                                  canonical evidence. Restore missing acceptance reference, repository, dependency/independence,
                                  assignment, priority, or expected review evidence only when authoritative evidence supports it.
                                  After the PM-owned gap is actually repaired, add one newer REVIEW FEEDBACK comment beginning
                                  CONTEXT_REPAIRED that identifies the repaired fields and tells the assigned developer to re-read
                                  the same child. Do not create a replacement task. If the missing information cannot be resolved
                                  from authoritative evidence, keep BLOCKED and name the actual external owner/evidence needed.
                                - For blockers not owned by Project Management, keep the truthful real state and record/refresh
                                  one BLOCKED comment naming the required owner and evidence.
                                - If every implementation child of an In Progress product parent is Done, add or refresh
                                  PRODUCT ACCEPTANCE READY only when there is no newer unresolved PRODUCT ACCEPTANCE FEEDBACK.
                                  When newer unresolved product feedback exists, do not publish another READY handoff here;
                                  leave the parent In Progress so work intake can decompose that feedback into remediation work.
                                  A later PRODUCT ACCEPTANCE READY is valid only after the feedback has been addressed by durable
                                  delivery evidence. Include the canonical acceptance page and completed evidence.

                                Do not use custom Plane states or labels.
                                """))
                .to("tack:act?id=pm-active-delivery-update&toolProfile=" + ProjectManagementToolProfile.ID)
                .to("tack:thought-exit?name=pm-active-delivery");

        from("direct:pm-work-intake")
                .routeId("pm-work-intake")
                .setProperty("decision.pm-has-product-for-delivery.result", constant(false))
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        One product parent for delivery planning: Todo handoff from PO, or existing In Progress product
                        parent with uncovered acceptance, acceptance feedback, or incomplete implementation-child coverage.
                        """))
                .to("tack:thought-enter?name=pm-intake&maxTokens=250")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Select at most one PRODUCT PARENT for delivery planning.

                                Prefer a Todo product parent: Todo is the authoritative PO -> PM handoff. Verify its canonical
                                product page contains usable user scope and testable Gherkin acceptance. Read the canonical
                                acceptance from its actual Gitea documentation reference rather than relying on a duplicated
                                summary in Plane. Do not require a magic readiness comment.

                                Otherwise select an In Progress product parent when delivery decomposition remains incomplete
                                or when newer PRODUCT ACCEPTANCE FEEDBACK is unresolved and is not yet represented by adequate
                                remediation implementation children. Treat newer product feedback as invalidating any older
                                PRODUCT ACCEPTANCE READY handoff until remediation is durably completed.

                                Never implement the product parent itself. Never treat an implementation child as a product
                                slice. Do not create new product scope.
                                """))
                .to("tack:act?id=pm-intake-review&toolProfile=" + ProjectManagementToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                                Is one Todo or qualifying In Progress product parent selected with canonical product acceptance
                                actually readable and a real delivery-decomposition need?
                                """))
                .to("tack:decide?id=pm-has-product-for-delivery")
                .to("tack:thought-exit?name=pm-intake");

        from("direct:pm-task-planning-loop")
                .routeId("pm-task-planning-loop")
                .setProperty("pmTaskPlanningCycle", constant(0))
                .setProperty("pmTaskPlanningMadeProgress", constant(false))
                .setProperty("pmTaskPlanningPassProgress", constant(true))
                .setProperty("decision.pm-task-planning-done.result", constant(false))
                .loopDoWhile(simple("${exchangeProperty.pmTaskPlanningPassProgress} == true && ${exchangeProperty.pmTaskPlanningCycle} < 10"))
                    .setProperty("pmTaskPlanningCycle", simple("${exchangeProperty.pmTaskPlanningCycle++}"))
                    .setProperty("pmTaskPlanningPassProgress", constant(false))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Second-stage decomposition of one product parent: acceptance behaviors, existing implementation
                        children, technical coverage, dependencies, assignees, product-acceptance feedback, and the next
                        missing discrete delivery outcome.
                        """))
                    .to("tack:thought-enter?name=pm-planning&maxTokens=300")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Decompose the selected PRODUCT PARENT into IMPLEMENTATION CHILDREN.

                                    Product-owner slices are intentionally user-facing and may span several technical changes.
                                    Your job is the second decomposition: identify independently finishable technical outcomes
                                    whose combined delivery satisfies the parent's existing Gherkin acceptance.

                                    Freshly read the canonical product acceptance from its Gitea documentation reference, then
                                    inspect the parent, its existing implementation children, and any newer unresolved PRODUCT
                                    ACCEPTANCE FEEDBACK. Build a compact coverage map from each acceptance behavior to existing
                                    children so you do not duplicate work.

                                    Unresolved PRODUCT ACCEPTANCE FEEDBACK is authoritative evidence that the prior delivery did
                                    not actually satisfy the named existing acceptance behavior. An implementation child that was
                                    already Done before that feedback does NOT count as adequate remediation merely because it
                                    nominally covered the same Gherkin scenario. Create or refine a distinct remediation child
                                    after the feedback that references the exact feedback and failed existing scenario. Do not
                                    invent new product scope or rewrite acceptance criteria. Once that feedback has an adequate
                                    remediation child, normal dependency/assignment rules apply.

                                    Create or refine exactly one missing, remediation, or oversized implementation child per pass.

                                    Each implementation child must:
                                    - be linked as a child of the selected product parent
                                    - describe one independently finishable technical outcome, not a new user-facing product slice
                                    - reference the parent's canonical acceptance page and the acceptance behavior(s) it covers
                                    - for remediation, reference the specific PRODUCT ACCEPTANCE FEEDBACK it addresses
                                    - have one named developer assignee, priority, source repository, dependencies or explicit independence
                                    - name one concrete next implementation step and expected review/validation evidence
                                    - use Todo when its dependencies are satisfied and it is executable now
                                    - use Backlog when a declared dependency prevents execution now

                                    When first taking a Todo product parent into delivery, move the parent Todo -> In Progress.
                                    Never assign the product parent to the developer as their implementation task.
                                    Never create overlapping children merely because implementation has already begun.
                                    """))
                    .to("tack:act?id=pm-actionable-dev-task&toolProfile=" + ProjectManagementToolProfile.ID)
                    .choice()
                        .when(exchangeProperty("act.pm-actionable-dev-task.durableProgress").isEqualTo(true))
                            .setProperty("pmTaskPlanningMadeProgress", constant(true))
                            .setProperty("pmTaskPlanningPassProgress", constant(true))
                    .end()
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is this product parent's delivery decomposition adequate now?
                                    Return true only when every existing Gherkin acceptance behavior is covered by one or more
                                    non-duplicative implementation children, each child is small enough to finish independently,
                                    dependencies are explicit, and every currently executable child is assigned in Todo.

                                    Also return false when newer unresolved PRODUCT ACCEPTANCE FEEDBACK is not yet represented by
                                    a distinct post-feedback remediation implementation child that explicitly addresses the failed
                                    existing scenario. Older Done children created before that feedback do not satisfy this check.
                                    Once adequate remediation work exists, it may be Todo, In Progress, or Done according to its
                                    actual delivery state; planning need not wait for implementation completion.

                                    Return false while acceptance behavior remains uncovered, required remediation is missing,
                                    or a child is still too broad.
                                    """))
                    .to("tack:decide?id=pm-task-planning-done")
                    .choice()
                        .when(exchangeProperty("decision.pm-task-planning-done.result").isEqualTo(true))
                            .setProperty("pmTaskPlanningPassProgress", constant(false))
                    .end()
                    .to("tack:thought-exit?name=pm-planning")
                .end();

        from("direct:pm-team-sync")
                .routeId("pm-team-sync")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Read recent project chat and participate when a real coordination exchange helps. Reply to
                                direct questions, review/blocker handoffs, or ask one named question that unblocks work.
                                Avoid generic status. Plane remains authoritative for assignment and state.
                                """))
                .to("tack:act?id=pm-team-chat-sync&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:pm-documentation-status-sync")
                .routeId("pm-documentation-status-sync")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Read only current mission delivery/navigation anchors when needed. Keep daily work state in
                                Plane. Do not sweep documentation.
                                """))
                .to("tack:act?id=pm-workspace-status-read&toolProfile=" + SourceControlToolProfile.ID);
    }
}
