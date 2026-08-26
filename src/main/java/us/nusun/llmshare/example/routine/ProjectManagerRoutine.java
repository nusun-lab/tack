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
                .main("Own project management for the whole mission through completion: establish the shared workspace, consume Product Owner handoffs, decompose product parents into implementation children, coordinate review/integration/blockers, and return completed outcomes for product acceptance.", "direct:example-software-team-project-manager")
                .subroutine("bootstrap", "Establish only the shared Plane, source-control, documentation, and team-chat foundation required for delivery.", "direct:example-software-team-project-manager-bootstrap")
                .subroutine("coordination", "Operate an established delivery workspace through decomposition, review, integration, blockers, and product acceptance.", "direct:example-software-team-project-manager-coordination")
                .subroutine("task-planning", "Decompose one Todo or active product parent into discrete implementation children whose combined coverage satisfies product acceptance.", "direct:example-software-team-project-manager-task-planning")
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
                    .setHeader(TackHeaders.PROMPT, constant("Review current mission completion once using minimal authoritative evidence. One unfinished parent, child, blocker, review, merge, product question, or acceptance follow-up means incomplete. Do not mutate."))
                    .to("tack:act?id=pm-mission-status-review&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("Is the mission complete from Project Management's perspective? Return true only when all required product parents are Done and no delivery, blocker, PRODUCT QUESTION, review, integration, assignment, or acceptance follow-up remains."))
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
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Durable delivery anchors: project basics, selected product parent, implementation coverage, product questions, source/review/integration evidence, and team chat."))
                .to("tack:thought-enter?name=project-manager-routine&maxTokens=500");

        from("direct:pm-project-manager-root-exit")
                .routeId("pm-project-manager-root-exit")
                .to("tack:thought-exit?name=project-manager-routine");

        from("direct:pm-project-basics-check")
                .routeId("pm-project-basics-check")
                .setProperty("decision.collaboration-setup-complete.result", constant(false))
                .setHeader(TackHeaders.PROMPT, constant("""
                        Verify only the shared foundation needed to enter coordination: exactly one current-mission Plane project,
                        documentation repository/navigation, source repository under agent-workspace, and mission team-chat channel,
                        all accessible to this Project Manager run. Do not require every current or future teammate to already have
                        membership everywhere; pm-team-onboarding owns roster membership alignment after the foundation exists.
                        Do not mutate.
                        """))
                .to("tack:act?id=pm-workspace-basics-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("Are Plane, source repo, docs/navigation, and mission chat verified and readable by this Project Manager run? Return true without requiring full-team membership."))
                .to("tack:decide?id=collaboration-setup-complete");

        from("direct:pm-project-bootstrap")
                .routeId("pm-project-bootstrap")
                .setProperty("collaborationSetupAttempt", constant(0))
                .setProperty("pmBootstrapMadeProgress", constant(true))
                .loopDoWhile(simple("${exchangeProperty.decision.collaboration-setup-complete.result} != true && ${exchangeProperty.pmBootstrapMadeProgress} == true && ${exchangeProperty.collaborationSetupAttempt} < 6"))
                    .setProperty("collaborationSetupAttempt", simple("${exchangeProperty.collaborationSetupAttempt++}"))
                    .setProperty("pmBootstrapMadeProgress", constant(false))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Bootstrap only: Plane project, source/docs repositories, mission chat, and navigation. Product work and full-team membership are not bootstrap prerequisites."))
                    .to("tack:thought-enter?name=pm-bootstrap&maxTokens=300")
                    .setHeader(TackHeaders.PROMPT, constant("Create or confirm exactly one current-mission Plane project. Preserve an existing useful project. Do not create product or implementation work."))
                    .to("tack:act?id=plane-project-provisioning&toolProfile=" + WorkspaceCoordinationToolProfile.ID)
                    .choice().when(exchangeProperty("act.plane-project-provisioning.durableProgress").isEqualTo(true)).setProperty("pmBootstrapMadeProgress", constant(true)).end()
                    .setHeader(TackHeaders.PROMPT, constant("Create or confirm exactly one markdown documentation repository under agent-workspace. Do not create planned work in source control."))
                    .to("tack:act?id=documentation-repository-provisioning&toolProfile=" + WorkspaceCoordinationToolProfile.ID)
                    .choice().when(exchangeProperty("act.documentation-repository-provisioning.durableProgress").isEqualTo(true)).setProperty("pmBootstrapMadeProgress", constant(true)).end()
                    .setHeader(TackHeaders.PROMPT, constant("Create or confirm exactly one target source repository under agent-workspace."))
                    .to("tack:act?id=source-repository-provisioning&toolProfile=" + WorkspaceCoordinationToolProfile.ID)
                    .choice().when(exchangeProperty("act.source-repository-provisioning.durableProgress").isEqualTo(true)).setProperty("pmBootstrapMadeProgress", constant(true)).end()
                    .setHeader(TackHeaders.PROMPT, constant("Create or confirm one private current-mission team-chat channel accessible to Project Management. Never create a duplicate channel. Full roster membership is handled after bootstrap."))
                    .to("tack:act?id=chat-channel-setup&toolProfile=" + TeamChatToolProfile.ID)
                    .choice().when(exchangeProperty("act.chat-channel-setup.durableProgress").isEqualTo(true)).setProperty("pmBootstrapMadeProgress", constant(true)).end()
                    .setHeader(TackHeaders.PROMPT, constant("Create or update compact canonical navigation/project-basics documentation linking mission, repositories, Plane project, team chat, and role responsibilities."))
                    .to("tack:act?id=documentation-project-index&toolProfile=" + SourceControlWriteToolProfile.ID)
                    .choice().when(exchangeProperty("act.documentation-project-index.durableProgress").isEqualTo(true)).setProperty("pmBootstrapMadeProgress", constant(true)).end()
                    .to("direct:pm-project-basics-check")
                    .choice().when(exchangeProperty("decision.collaboration-setup-complete.result").isEqualTo(true)).setProperty("pmBootstrapMadeProgress", constant(false)).end()
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
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Current delivery pass: one actionable delivery/review/blocker/promotion/product-return item, then one product parent needing decomposition."))
                    .to("tack:thought-enter?name=pm-cycle&maxTokens=300")
                    .to("direct:pm-active-delivery-check")
                    .choice().when(exchangeProperty("act.pm-active-delivery-update.durableProgress").isEqualTo(true)).setProperty("pmMadeProgress", constant(true)).end()
                    .to("direct:pm-work-intake")
                    .choice()
                        .when(exchangeProperty("decision.pm-has-product-for-delivery.result").isEqualTo(true))
                            .to("direct:pm-task-planning-loop")
                            .choice().when(exchangeProperty("pmTaskPlanningMadeProgress").isEqualTo(true)).setProperty("pmMadeProgress", constant(true)).end()
                    .end()
                    .to("tack:thought-exit?name=pm-cycle")
                .end()
                .to("direct:pm-team-sync");

        from("direct:pm-team-onboarding")
                .routeId("pm-team-onboarding")
                .setHeader(TackHeaders.PROMPT, constant("Keep current mission teammates present in Plane and team chat where supported. Add missing current teammates after the shared foundation exists. Remove nobody without clear mission evidence."))
                .to("tack:act?id=pm-team-onboarding&toolProfile=" + WorkspaceCoordinationToolProfile.ID);

        from("direct:pm-active-delivery-check")
                .routeId("pm-active-delivery-check")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("One delivery-maintenance item: review, integration verification/recovery, blocker/product question, dependency promotion, cancelled coverage, or completed-parent handoff."))
                .to("tack:thought-enter?name=pm-active-delivery&maxTokens=300")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read at most one highest-value actionable current delivery item using fresh Plane and linked source evidence.
                        Priority:
                        1. REVIEW READY implementation child with no later PM disposition.
                        2. INTEGRATION READY child whose PR merged or approval became stale/closed/conflicted/changed. Skip clean open IR merely waiting on Director when other actionable PM work exists.
                        3. Todo/In Progress implementation child with current BLOCKED. If owner is Project Management, read canonical evidence needed to repair it. If owner is Product Owner, also read its parent and current PRODUCT QUESTION / PRODUCT HANDOFF ordering.
                        4. Backlog child whose dependencies may now be Done.
                        5. Cancelled implementation child whose acceptance behavior remains required and lacks superseding non-cancelled coverage.
                        6. In Progress product parent whose required non-cancelled implementation coverage may all be Done, including PRODUCT ACCEPTANCE READY / FEEDBACK ordering.
                        PRODUCT QUESTION is the authoritative PM -> PO return handoff for product ambiguity or unusable product acceptance. Cancelled children provide zero delivery coverage unless corresponding product scope itself was explicitly cancelled.
                        Do not mutate in this read.
                        """))
                .to("tack:act?id=pm-active-delivery-evidence-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Update only the item already read.
                        - Verified merged IR: implementation child In Progress -> Done.
                        - Stale/conflicted/closed-unmerged/changed IR: keep In Progress and add newer precise REVIEW FEEDBACK; SOURCE_REFRESH_REQUIRED for source drift/conflict.
                        - Acceptable REVIEW READY: add/refresh INTEGRATION READY with repo, PR, reviewedHead, reviewedBase, reviewedAt, validation, acceptanceRef. Director owns merge.
                        - Defective REVIEW READY: add precise REVIEW FEEDBACK.
                        - Backlog child with all dependencies Done and otherwise actionable/assigned: Backlog -> Todo.
                        - PM-owned BLOCKED caused by malformed/missing delivery context: repair same child from authoritative evidence, then add newer REVIEW FEEDBACK beginning CONTEXT_REPAIRED. If evidence cannot resolve it and actual missing judgment is product-owned, create/refresh PRODUCT QUESTION on the parent instead.
                        - Product-owned BLOCKED on an implementation child: create/refresh one PRODUCT QUESTION on its product parent, referencing the blocked child, exact product ambiguity, current canonical acceptance, and evidence needed from Product Owner. Keep child truthfully blocked. Do not rely on Mattermost as the handoff.
                        - If a newer Product Owner PRODUCT HANDOFF explicitly resolves the PRODUCT QUESTION associated with a blocked child, relay that resolved product decision back to the same child with newer REVIEW FEEDBACK beginning PRODUCT_QUESTION_RESOLVED and instruct the developer to re-read the same child. Do not create a replacement child.
                        - Required Cancelled child coverage: treat Cancelled as zero delivered coverage. Keep/reopen planning need on the parent so a non-cancelled replacement/remediation child can cover the still-required existing acceptance behavior. Never count a Cancelled child as satisfying decomposition or acceptance.
                        - Product parent: publish PRODUCT ACCEPTANCE READY only when every required acceptance behavior has durable Done coverage from non-cancelled children and no newer unresolved PRODUCT ACCEPTANCE FEEDBACK or PRODUCT QUESTION exists.
                        """))
                .to("tack:act?id=pm-active-delivery-update&toolProfile=" + ProjectManagementToolProfile.ID)
                .to("tack:thought-exit?name=pm-active-delivery");

        from("direct:pm-work-intake")
                .routeId("pm-work-intake")
                .setProperty("decision.pm-has-product-for-delivery.result", constant(false))
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("One product parent for delivery planning or product-handoff validation."))
                .to("tack:thought-enter?name=pm-intake&maxTokens=250")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Select at most one PRODUCT PARENT.
                        Prefer Todo initial PO handoff. Read its canonical Gitea acceptance. If the acceptance reference is missing/unreadable, Gherkin is unusable/contradictory, or product judgment is required before delivery can proceed, keep its current state and add/refresh one PRODUCT QUESTION comment on that parent naming the exact defect/question and evidence needed from Product Owner. PRODUCT QUESTION is a durable return handoff, not a Plane state.
                        Otherwise select an In Progress parent when decomposition is incomplete, required Cancelled coverage needs replacement, PRODUCT ACCEPTANCE FEEDBACK needs remediation, or a newer PO PRODUCT HANDOFF has resolved a prior PRODUCT QUESTION and delivery can resume.
                        Never invent product scope or implement the parent.
                        """))
                .to("tack:act?id=pm-intake-review&toolProfile=" + ProjectManagementToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("Is one Todo or qualifying In Progress product parent selected with readable usable acceptance and a real decomposition/replanning need? Return false when a PRODUCT QUESTION had to be raised and remains unresolved."))
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
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Second-stage decomposition: current Gherkin, existing non-cancelled coverage, feedback/questions, dependencies, assignees, and one missing technical outcome."))
                    .to("tack:thought-enter?name=pm-planning&maxTokens=300")
                    .setHeader(TackHeaders.PROMPT, constant("""
                        Freshly read canonical product acceptance and existing children. Create/refine exactly one missing, remediation, replacement, or oversized implementation child per pass.
                        Every required Gherkin behavior needs non-duplicative non-cancelled implementation coverage. A Cancelled child provides zero coverage and must be replaced when its acceptance behavior remains required. Older Done children do not satisfy newer unresolved PRODUCT ACCEPTANCE FEEDBACK that rejected their behavior; create a distinct post-feedback remediation child. Do not plan through an unresolved PRODUCT QUESTION.
                        Each child must be linked to parent, reference canonical acceptance and covered behaviors, have one developer assignee, priority, repo, dependencies/independence, next implementation step, expected review evidence, and Todo if executable or Backlog if dependency-blocked. Move initial Todo parent -> In Progress when taking delivery.
                        """))
                    .to("tack:act?id=pm-actionable-dev-task&toolProfile=" + ProjectManagementToolProfile.ID)
                    .choice().when(exchangeProperty("act.pm-actionable-dev-task.durableProgress").isEqualTo(true)).setProperty("pmTaskPlanningMadeProgress", constant(true)).setProperty("pmTaskPlanningPassProgress", constant(true)).end()
                    .setHeader(TackHeaders.PROMPT, constant("""
                        Is decomposition adequate? True only when every required Gherkin behavior has non-cancelled implementation coverage, each child is independently finishable with explicit dependencies, executable work is assigned Todo, every newer PRODUCT ACCEPTANCE FEEDBACK has a distinct post-feedback remediation child, no required coverage relies on Cancelled children, and no unresolved PRODUCT QUESTION blocks product intent. Otherwise false.
                        """))
                    .to("tack:decide?id=pm-task-planning-done")
                    .choice().when(exchangeProperty("decision.pm-task-planning-done.result").isEqualTo(true)).setProperty("pmTaskPlanningPassProgress", constant(false)).end()
                    .to("tack:thought-exit?name=pm-planning")
                .end();

        from("direct:pm-team-sync")
                .routeId("pm-team-sync")
                .setHeader(TackHeaders.PROMPT, constant("Read recent project chat and participate only when a real coordination exchange helps. Plane remains authoritative for assignments, blockers, PRODUCT QUESTION handoffs, and state."))
                .to("tack:act?id=pm-team-chat-sync&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:pm-documentation-status-sync")
                .routeId("pm-documentation-status-sync")
                .setHeader(TackHeaders.PROMPT, constant("Read only current mission delivery/navigation anchors when needed. Keep daily work state in Plane."))
                .to("tack:act?id=pm-workspace-status-read&toolProfile=" + SourceControlToolProfile.ID);
    }
}
