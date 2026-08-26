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
                .setProperty("projectManagerCycle", constant(0))
                .setProperty("decision.project-manager-done.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.project-manager-done.result").isNotEqualTo(true))
                    .setProperty("projectManagerCycle", simple("${exchangeProperty.projectManagerCycle++}"))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Current delivery pass: one active implementation child needing review/integration/promotion,
                        or one Todo/active product parent needing second-stage delivery decomposition.
                        """))
                    .to("tack:thought-enter?name=pm-cycle&maxTokens=300")
                    .to("direct:pm-team-onboarding")
                    .to("direct:pm-active-delivery-check")
                    .to("direct:pm-work-intake")
                    .choice()
                        .when(exchangeProperty("decision.pm-has-product-for-delivery.result").isEqualTo(true))
                            .to("direct:pm-task-planning-loop")
                    .end()
                    .to("direct:pm-team-sync")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is this PM pass locally complete because one concrete delivery action occurred:
                                    workspace setup was established; one implementation review/integration/promotion was
                                    coordinated; one product parent was adequately decomposed into implementation children;
                                    one acceptance handoff was produced; or no current PM action exists and that was verified?
                                    """))
                    .to("tack:decide?id=project-manager-done")
                    .to("tack:thought-exit?name=pm-cycle")
                .end();

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
                        One delivery-maintenance item: In Progress implementation child awaiting review or merge verification,
                        blocked Todo/In Progress child, Backlog child whose dependencies may now be satisfied, or active
                        product parent whose implementation children may all be Done.
                        """))
                .to("tack:thought-enter?name=pm-active-delivery&maxTokens=300")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Read at most one highest-value current delivery item using Plane's real states only.

                                Priority:
                                1. In Progress implementation child with INTEGRATION READY: inspect the linked PR and determine
                                   whether the Director has merged the exact reviewed head.
                                2. In Progress implementation child with current REVIEW READY and no later PM disposition:
                                   inspect its acceptance reference, diff, changed files, validation, and linked PR.
                                3. Todo/In Progress implementation child with a current BLOCKED comment needing coordination.
                                4. Backlog implementation child whose declared dependencies may now all be Done.
                                5. In Progress product parent whose implementation children may all be Done.

                                REVIEW READY, INTEGRATION READY, BLOCKED, and PRODUCT ACCEPTANCE READY are handoff/evidence
                                comments, never Plane states. Do not search for nonexistent Review or Blocked states.
                                Do not mutate in this read.
                                """))
                .to("tack:act?id=pm-active-delivery-evidence-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                                Update at most the delivery item already read.

                                - If an INTEGRATION READY implementation child's exact reviewed PR/head is verified merged,
                                  move that implementation child In Progress -> Done.
                                - If a REVIEW READY implementation child is acceptable against its referenced product
                                  acceptance and available validation, keep it In Progress and add/refresh one INTEGRATION READY
                                  comment containing repository, PR, reviewed head commit, validation, and acceptance reference.
                                  The Director owns the merge. Do not move the task to Done before merge verification.
                                - If review finds a defect or unmet acceptance behavior, keep the task In Progress and add one
                                  precise REVIEW FEEDBACK comment. Do not create a duplicate replacement task.
                                - If a Backlog implementation child's dependencies are all Done and the task is otherwise
                                  actionable and assigned, move it Backlog -> Todo.
                                - Keep blocked work in its truthful real state and record/refresh one BLOCKED comment naming
                                  the required owner and evidence.
                                - If every implementation child of an In Progress product parent is Done, keep the parent
                                  In Progress and add/refresh PRODUCT ACCEPTANCE READY with the canonical acceptance page and
                                  completed evidence for Product Ownership.

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
                                product page contains usable user scope and testable Gherkin acceptance. Do not require a magic
                                readiness comment.

                                Otherwise select an In Progress product parent only when delivery decomposition remains
                                incomplete or Product Ownership has supplied PRODUCT ACCEPTANCE FEEDBACK that is not yet
                                represented by an implementation child.

                                Never implement the product parent itself. Never treat an implementation child as a product
                                slice. Do not create new product scope.
                                """))
                .to("tack:act?id=pm-intake-review&toolProfile=" + ProjectManagementToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                                Is one Todo or qualifying In Progress product parent selected with usable product acceptance
                                and a real delivery-decomposition need?
                                """))
                .to("tack:decide?id=pm-has-product-for-delivery")
                .to("tack:thought-exit?name=pm-intake");

        from("direct:pm-task-planning-loop")
                .routeId("pm-task-planning-loop")
                .setProperty("pmTaskPlanningCycle", constant(0))
                .setProperty("decision.pm-task-planning-done.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.pm-task-planning-done.result").isNotEqualTo(true))
                    .setProperty("pmTaskPlanningCycle", simple("${exchangeProperty.pmTaskPlanningCycle++}"))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Second-stage decomposition of one product parent: acceptance behaviors, existing implementation
                        children, technical coverage, dependencies, assignees, and the next missing discrete delivery outcome.
                        """))
                    .to("tack:thought-enter?name=pm-planning&maxTokens=300")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Decompose the selected PRODUCT PARENT into IMPLEMENTATION CHILDREN.

                                    Product-owner slices are intentionally user-facing and may span several technical changes.
                                    Your job is the second decomposition: identify independently finishable technical outcomes
                                    whose combined delivery satisfies the parent's existing Gherkin acceptance.

                                    First inspect the parent and its existing implementation children. Build a compact coverage
                                    map from each acceptance behavior to existing children so you do not duplicate work.
                                    Then create or refine exactly one missing or oversized implementation child per pass.

                                    Each implementation child must:
                                    - be linked as a child of the selected product parent
                                    - describe one independently finishable technical outcome, not a new user-facing product slice
                                    - reference the parent's canonical acceptance page and the acceptance behavior(s) it covers
                                    - have one named developer assignee, priority, source repository, dependencies or explicit independence
                                    - name one concrete next implementation step and expected review/validation evidence
                                    - use Todo when its dependencies are satisfied and it is executable now
                                    - use Backlog when a declared dependency prevents execution now

                                    When first taking a Todo product parent into delivery, move the parent Todo -> In Progress.
                                    Never assign the product parent to the developer as their implementation task.
                                    Never create overlapping children merely because implementation has already begun.
                                    """))
                    .to("tack:act?id=pm-actionable-dev-task&toolProfile=" + ProjectManagementToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is this product parent's delivery decomposition adequate now?
                                    Return true only when every existing Gherkin acceptance behavior is covered by one or more
                                    non-duplicative implementation children, each child is small enough to finish independently,
                                    dependencies are explicit, and every currently executable child is assigned in Todo.
                                    Return false while acceptance behavior remains uncovered or a child is still too broad.
                                    """))
                    .to("tack:decide?id=pm-task-planning-done")
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
