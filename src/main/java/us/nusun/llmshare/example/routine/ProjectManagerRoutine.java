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
                    .choice()
                        .when(exchangeProperty("decision.collaboration-setup-complete.result").isEqualTo(true))
                            .to("direct:pm-coordination-loop")
                    .end()
                    .setHeader(TackHeaders.PROMPT, constant("Review current mission completion once using minimal authoritative evidence. One unfinished parent, child, current unresolved blocker, review, merge, product question, topology finding, or acceptance follow-up means incomplete. Superseded historical handoff comments are not current blockers. Do not mutate."))
                    .to("tack:act?id=pm-mission-status-review&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("Is the mission complete from Project Management's perspective? Return true only when all required product parents are Done and no current unresolved delivery, blocker, PRODUCT QUESTION, review, integration, assignment, topology investigation/finding, or acceptance follow-up remains. Ignore historical handoffs superseded by newer disposition evidence."))
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
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Durable delivery anchors: project basics, selected product parent, implementation coverage, product questions, dependency topology, source/review/integration leases, and team chat."))
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
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Current delivery pass: one actionable delivery/review/blocker/promotion/product-return/topology item, then one product parent needing decomposition."))
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
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("One delivery-maintenance item: topology finding/application, review, integration verification/recovery, actionable blocker/product question, dependency promotion/repair, cancelled coverage, or completed-parent handoff."))
                .to("tack:thought-enter?name=pm-active-delivery&maxTokens=300")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read at most one highest-value ACTIONABLE current delivery item using fresh Plane and linked source evidence.
                        Waiting handoffs are not actionable merely because they still exist; skip an unchanged item already waiting
                        on another owner and continue to the next priority.

                        Priority:
                        1. In Progress TOPOLOGY INVESTIGATION implementation child with current TECHNICAL FINDING and no later PM disposition.
                        2. Done TOPOLOGY INVESTIGATION whose TECHNICAL FINDING affects an unfinished product parent and has no later PM TOPOLOGY_APPLIED or PRODUCT QUESTION disposition.
                        3. REVIEW READY implementation child with no later PM disposition.
                        4. INTEGRATION READY child whose PR merged or approval became stale/closed/conflicted/changed. Skip clean open IR merely waiting on Director when other actionable PM work exists.
                        5. Todo/In Progress implementation child with current BLOCKED only when Project Management can act now:
                           - PM owns the blocker and fresh authoritative evidence can repair or redispose it; or
                           - Product Owner owns it and no current PRODUCT QUESTION has yet been emitted; or
                           - a newer PRODUCT HANDOFF resolves the current PRODUCT QUESTION and PM must relay the resolution; or
                           - the named owner is not routable by this routine set and PM must normalize coordination ownership; or
                           - materially newer evidence changed the blocker.
                           If a current PRODUCT QUESTION or PM-coordinated external blocker is already waiting with no newer answer/evidence,
                           skip that blocked child and continue to lower priorities.
                        6. Backlog child whose dependencies may now be Done, whose dependency edge points to Cancelled work, or whose dependency references a product parent rather than a valid implementation/external prerequisite.
                        7. Cancelled implementation child whose acceptance behavior remains required, lacks superseding non-cancelled coverage, or is still named as a dependency by unfinished required work.
                        8. In Progress product parent whose children may now all be terminally dispositioned and required Done coverage complete, including acceptance-lease and topology ordering.

                        PRODUCT QUESTION is the authoritative PM -> PO return handoff for product ambiguity or unusable product acceptance.
                        TECHNICAL FINDING is the Developer -> PM return handoff for a TOPOLOGY INVESTIGATION and does not require a PR.
                        TOPOLOGY_APPLIED is the PM disposition proving a TECHNICAL FINDING has been incorporated into the current delivery graph.
                        In the current routine set, routable BLOCKED coordination owners are Project Management and Product Owner;
                        external conditions are coordinated by Project Management rather than assigning a fictitious internal owner.
                        Cancelled children provide zero delivery coverage and cannot satisfy dependency edges unless the corresponding
                        product scope itself was explicitly cancelled. Do not mutate in this read.
                        """))
                .to("tack:act?id=pm-active-delivery-evidence-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Update only the item already read.
                        - TOPOLOGY INVESTIGATION with sufficient current TECHNICAL FINDING: move that investigation child In Progress -> Done and add a concise PM disposition comment beginning TECHNICAL_FINDING_ACCEPTED. This accepts the evidence for replanning but does NOT by itself mean the topology has been applied.
                        - Done TOPOLOGY INVESTIGATION with accepted current TECHNICAL FINDING and no later topology disposition: if PM can safely apply the finding now, update the affected dependency/task topology and add a newer comment beginning TOPOLOGY_APPLIED referencing the finding and exact graph changes. If the finding instead requires product judgment, create/refresh PRODUCT QUESTION on the affected product parent; that newer question is the disposition. Do not leave an accepted finding without one of those later dispositions.
                        - Verified merged IR: implementation child In Progress -> Done.
                        - Stale/conflicted/closed-unmerged/changed IR: keep In Progress and add newer precise REVIEW FEEDBACK; SOURCE_REFRESH_REQUIRED for source drift/conflict. A closed-unmerged PR is stale review evidence; Developer must refresh the same task/branch and create or reopen a usable PR only when supported by current source-control capabilities, then publish fresh REVIEW READY. Never treat a closed-unmerged PR as current integration evidence.
                        - Acceptable REVIEW READY: add/refresh INTEGRATION READY with repo, PR, reviewedHead, reviewedBaseRef, reviewedBaseSha, reviewedAt, validation, acceptanceRef. Resolve and record the exact current base commit SHA reviewed, not merely the base branch name. Director owns merge.
                        - Defective REVIEW READY: add precise REVIEW FEEDBACK.
                        - Backlog child with all dependencies Done and otherwise actionable/assigned: Backlog -> Todo.
                        - PM-owned BLOCKED caused by malformed/missing delivery context: repair same child from authoritative evidence, then add newer REVIEW FEEDBACK beginning CONTEXT_REPAIRED. If evidence cannot resolve it and actual missing judgment is product-owned, create/refresh PRODUCT QUESTION on the parent instead.
                        - Product-owned BLOCKED on an implementation child: create/refresh one PRODUCT QUESTION on its product parent only when no current unresolved question already represents that blocker. Reference the blocked child, exact product ambiguity, current canonical acceptance, and evidence needed from Product Owner. Keep child truthfully blocked. Do not refresh an unchanged question merely to create activity.
                        - BLOCKED with a named owner other than Project Management or Product Owner: normalize once to a newer BLOCKED handoff owned by Project Management as coordination owner, preserving the actual external/person/system dependency and exact evidence needed. Director is not a generic blocker consumer; Director only consumes INTEGRATION READY.
                        - If a newer Product Owner PRODUCT HANDOFF explicitly resolves the PRODUCT QUESTION associated with a blocked child, relay that resolved product decision back to the same child with newer REVIEW FEEDBACK beginning PRODUCT_QUESTION_RESOLVED and instruct the developer to re-read the same child. Do not create a replacement child. The resolving PRODUCT HANDOFF invalidates all older PRODUCT ACCEPTANCE READY leases on that parent.
                        - If an unchanged blocker is already waiting on its routable owner and no newer evidence exists, make no mutation. It must not prevent unrelated dependency promotion, replanning, review, or acceptance work from being selected on a later read.
                        - Required Cancelled child coverage: treat Cancelled as zero delivered coverage. Keep/reopen planning need on the parent so a non-cancelled replacement/remediation child can cover the still-required existing acceptance behavior. Never count a Cancelled child as satisfying decomposition or acceptance.
                        - An unfinished child must not retain a dependency on Cancelled work or on its own product parent. When authoritative planning evidence identifies the specific replacement/remediation child for a cancelled dependency, rewire the dependent child to that replacement. Remove an invalid dependency only when the technical dependency is demonstrably no longer required; do not invent sequencing.
                        - Before PRODUCT ACCEPTANCE READY, every child under the product parent must be terminally dispositioned: Done, or Cancelled with explicit evidence it is superseded/non-required and contributes no required coverage. Any Todo/In Progress child, active TOPOLOGY INVESTIGATION, accepted-but-unapplied TECHNICAL FINDING, unresolved PRODUCT QUESTION, or newer PRODUCT ACCEPTANCE FEEDBACK blocks READY.
                        - PRODUCT ACCEPTANCE READY is a lease over the CURRENT product contract. Publish a fresh READY only after the latest PRODUCT QUESTION has been resolved by PRODUCT HANDOFF and all delivery evidence has been reevaluated against that newer handoff. READY must be newer than every PRODUCT ACCEPTANCE FEEDBACK, PRODUCT QUESTION, and PRODUCT HANDOFF on the parent.
                        """))
                .to("tack:act?id=pm-active-delivery-update&toolProfile=" + ProjectManagementToolProfile.ID)
                .to("tack:thought-exit?name=pm-active-delivery");

        from("direct:pm-work-intake")
                .routeId("pm-work-intake")
                .setProperty("decision.pm-has-product-for-delivery.result", constant(false))
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("One product parent for delivery planning, topology recovery, or product-handoff validation."))
                .to("tack:thought-enter?name=pm-intake&maxTokens=250")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Select at most one PRODUCT PARENT.
                        Prefer Todo initial PO handoff. Read its canonical Gitea acceptance. If the acceptance reference is missing/unreadable, Gherkin is unusable/contradictory, or product judgment is required before delivery can proceed, keep its current state and add/refresh one PRODUCT QUESTION comment on that parent naming the exact defect/question and evidence needed from Product Owner. PRODUCT QUESTION is a durable return handoff, not a Plane state.
                        Otherwise select an In Progress parent when decomposition is incomplete, required Cancelled coverage or invalid dependency edges need replacement/rewiring, PRODUCT ACCEPTANCE FEEDBACK needs remediation, the reachable unfinished dependency graph has no viable executable frontier, an accepted TECHNICAL FINDING has not yet been dispositioned/applied, any child remains nonterminal without a required delivery reason, or a newer PO PRODUCT HANDOFF has resolved a prior PRODUCT QUESTION and delivery must reevaluate against the current product contract.
                        Never invent product scope or implement the parent.
                        """))
                .to("tack:act?id=pm-intake-review&toolProfile=" + ProjectManagementToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("Is one Todo or qualifying In Progress product parent selected with readable usable acceptance and a real decomposition/replanning/topology/lease-recovery need? Return false when a PRODUCT QUESTION had to be raised and remains unresolved."))
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
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Second-stage decomposition: current Gherkin, existing non-cancelled coverage, feedback/questions, transitive dependency-graph viability, technical findings, assignees, and one missing technical outcome or invalid dependency edge."))
                    .to("tack:thought-enter?name=pm-planning&maxTokens=300")
                    .setHeader(TackHeaders.PROMPT, constant("""
                        Freshly read canonical product acceptance and existing children. Build both:
                        - a coverage map from required Gherkin behaviors to implementation children; and
                        - the REACHABLE TRANSITIVE dependency graph starting from the selected parent's unfinished required children.

                        Follow every referenced non-Done implementation dependency recursively even when it belongs to a different
                        product parent. Continue until each path reaches Done implementation work, an explicit external prerequisite,
                        or a nonterminal implementation frontier. Cross-product implementation dependencies are allowed, but they
                        must participate in the same cycle/frontier analysis. A product parent itself is never a valid dependency
                        target for one of its implementation children, especially the child's own parent.

                        Create or refine exactly one missing, remediation, replacement, oversized child, invalid dependency edge,
                        terminal disposition, or topology-recovery frontier per pass. Every required Gherkin behavior needs non-duplicative
                        non-cancelled implementation coverage. A Cancelled child provides zero coverage and must be replaced when its
                        acceptance behavior remains required. Older Done children do not satisfy newer unresolved PRODUCT ACCEPTANCE
                        FEEDBACK that rejected their behavior. Do not plan through an unresolved PRODUCT QUESTION.

                        If a nonterminal child is no longer required because its work is demonstrably superseded or redundant, explicitly
                        Cancel it with evidence before parent acceptance. Do not leave obsolete Todo/In Progress children stranded under a
                        parent that is otherwise ready for acceptance.

                        The reachable unfinished dependency graph must be viable:
                        - no child may depend on itself;
                        - no implementation child may depend on its own product parent or use product-parent completion as a prerequisite;
                        - no cycle is allowed, including cycles that cross product-parent boundaries;
                        - no unfinished child may depend on Cancelled work;
                        - each Backlog dependency chain must eventually reach Done prerequisites or a nonterminal frontier that can progress;
                        - every required unfinished connected component must contain at least one progress frontier: Todo, In Progress,
                          or a durable BLOCKED handoff with a routable current owner;
                        - routable workflow blocker owners in this routine set are Project Management and Product Owner. External
                          conditions are represented as Project Management-owned coordination blockers with the external condition named;
                        - a dependency-free unfinished delivery child is executable and therefore belongs in Todo, not Backlog.

                        If a cycle/invalid edge exists and current technical/acceptance evidence supports a safe correction, break or
                        rewire exactly one edge. If a dependency points to Cancelled work, rewire it to the known replacement/remediation
                        child when that replacement carries the required prerequisite; otherwise remove the edge only when the prerequisite
                        is demonstrably unnecessary. Never make a dependent silently treat Cancelled as Done.

                        If the topology is invalid but Project Management cannot safely infer the technical correction from current
                        evidence, DO NOT repeatedly yield with no recovery edge and DO NOT guess. Classify the missing judgment:
                        - product intent/acceptance ambiguity -> create/refresh PRODUCT QUESTION on the selected product parent;
                        - technical sequencing/integration ambiguity -> create exactly one dependency-free Todo implementation child
                          assigned to the Developer whose description begins TOPOLOGY INVESTIGATION. Reference all affected work-item ids,
                          the observed cycle/invalid edges, relevant product acceptance references, source repository, and the exact
                          technical question. Its expected durable outcome is a Plane comment beginning TECHNICAL FINDING with observed
                          source facts and a recommended evidence-backed sequencing/dependency correction. No PR or code change is required
                          for a topology investigation unless source modification is independently justified. Do not create a duplicate
                          investigation when an equivalent Todo/In Progress investigation already exists.

                        Each normal implementation child must be linked to parent, reference canonical acceptance and covered behaviors,
                        have one developer assignee, priority, repo, dependencies/independence, next implementation step, expected review
                        evidence, and Todo if executable or Backlog only when genuinely dependency-blocked. Move initial Todo parent ->
                        In Progress when taking delivery.
                        """))
                    .to("tack:act?id=pm-actionable-dev-task&toolProfile=" + ProjectManagementToolProfile.ID)
                    .choice().when(exchangeProperty("act.pm-actionable-dev-task.durableProgress").isEqualTo(true)).setProperty("pmTaskPlanningMadeProgress", constant(true)).setProperty("pmTaskPlanningPassProgress", constant(true)).end()
                    .setHeader(TackHeaders.PROMPT, constant("""
                        Is delivery planning sufficiently routed for now?
                        Return true when the decomposition itself is viable and all normal adequacy conditions below hold, OR when
                        the only remaining known topology defect is already represented by exactly one current actionable Todo/In Progress
                        TOPOLOGY INVESTIGATION for that exact ambiguity. In the latter case, do not claim the dependency graph is valid;
                        return true only to stop this planning loop while durable technical evidence is being gathered.

                        For a fully viable decomposition require:
                        - every required Gherkin behavior has non-cancelled implementation coverage;
                        - each child is independently finishable or explicitly terminally dispositioned;
                        - the reachable transitive dependency graph has no self-dependency, child->own-parent dependency, or cycle,
                          including across product-parent boundaries;
                        - no unfinished child depends on Cancelled work;
                        - every required unfinished dependency component has a viable progress frontier in Todo, In Progress, or an
                          explicit durable BLOCKED handoff with a routable owner;
                        - every dependency-free or dependency-satisfied executable child is assigned in Todo rather than Backlog;
                        - every newer PRODUCT ACCEPTANCE FEEDBACK has a distinct post-feedback remediation child;
                        - no required coverage relies on Cancelled children;
                        - no unresolved PRODUCT QUESTION blocks product intent; and
                        - no accepted TECHNICAL FINDING remains without a later TOPOLOGY_APPLIED or PRODUCT QUESTION disposition.

                        Return false when an invalid graph has neither a safe repair nor an existing actionable topology investigation,
                        when required recovery work is missing, when a current TECHNICAL FINDING is available but has not yet been dispositioned,
                        or when an obsolete nonterminal child still needs explicit cancellation/disposition.
                        """))
                    .to("tack:decide?id=pm-task-planning-done")
                    .choice().when(exchangeProperty("decision.pm-task-planning-done.result").isEqualTo(true)).setProperty("pmTaskPlanningPassProgress", constant(false)).end()
                    .to("tack:thought-exit?name=pm-planning")
                .end();

        from("direct:pm-team-sync")
                .routeId("pm-team-sync")
                .setHeader(TackHeaders.PROMPT, constant("Read recent project chat and participate only when a real coordination exchange helps. Plane remains authoritative for assignments, blockers, PRODUCT QUESTION handoffs, topology findings/dispositions, and state."))
                .to("tack:act?id=pm-team-chat-sync&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:pm-documentation-status-sync")
                .routeId("pm-documentation-status-sync")
                .setHeader(TackHeaders.PROMPT, constant("Read only current mission delivery/navigation anchors when needed. Keep daily work state in Plane."))
                .to("tack:act?id=pm-workspace-status-read&toolProfile=" + SourceControlToolProfile.ID);
    }
}
