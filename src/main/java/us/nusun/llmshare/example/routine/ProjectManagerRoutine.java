package us.nusun.llmshare.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
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
    public List<RoutineDefinition> routines() { return List.of(routine()); }

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
                    .to("direct:pm-orientation")
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Choose exactly one highest-priority Project Manager focus for the next focused pass.
                            Allowed focus values, in priority order when genuinely actionable:
                            - bootstrap: any shared Plane/source/docs/chat foundation basic is missing or unusable.
                            - active-delivery: a current review, integration disposition, merged-PR closure, topology finding, blocker, dependency promotion/repair, cancelled coverage, product-question return, or completed-parent handoff can be acted on now. Prefer this promptly for REVIEW READY or post-merge work so integration latency stays low.
                            - planning: usable product work exists and the assigned executable Developer Todo backlog is too shallow, incomplete, or needs topology/remediation planning. Build a modest backlog without inventing work.
                            - onboarding: the shared foundation exists but current mission roster membership needs alignment.
                            - coordination: a fresh team exchange needs Project Manager participation.
                            - documentation: a concrete durable navigation/status curation need exists; daily work state belongs in Plane.
                            - completion: durable evidence makes mission completion plausible enough for the dedicated completion check.
                            - wait: no focus above is currently useful; do not create ceremonial activity.
                            Pick one focus only. Preserve current delivery ownership and handoff semantics. Return only the focus object.
                            """))
                    .to("tack:create?id=pm-priority-focus&schemaId=project-manager-priority-focus&schema=classpath:schemas/example/software-team/project-manager-priority-focus-schema.json")
                    .unmarshal().json(JsonLibrary.Jackson, PriorityFocus.class)
                    .process(exchange -> exchange.setProperty("pmPriorityFocus", exchange.getMessage().getBody(PriorityFocus.class).focus()))
                    .choice()
                        .when(exchangeProperty("pmPriorityFocus").isEqualTo("bootstrap"))
                            .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Focused workspace bootstrap until shared collaboration basics are usable or no further bootstrap progress is possible."))
                            .to("tack:thought-enter?name=pm-bootstrap-focus&maxTokens=350")
                            .to("direct:pm-bootstrap-focus-dispatch")
                            .to("tack:thought-exit?name=pm-bootstrap-focus")
                        .when(exchangeProperty("pmPriorityFocus").isEqualTo("active-delivery"))
                            .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Focused delivery supervision: stay on current review/integration/blocker/topology/promotion work while it continues producing useful dispositions."))
                            .to("tack:thought-enter?name=pm-active-delivery-focus&maxTokens=400")
                            .to("direct:pm-active-delivery-focus")
                            .to("tack:thought-exit?name=pm-active-delivery-focus")
                        .when(exchangeProperty("pmPriorityFocus").isEqualTo("planning"))
                            .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Focused delivery planning: decompose product work one implementation child at a time until a modest executable Developer backlog exists or planning can no longer progress."))
                            .to("tack:thought-enter?name=pm-planning-focus&maxTokens=400")
                            .to("direct:pm-planning-focus")
                            .to("tack:thought-exit?name=pm-planning-focus")
                        .when(exchangeProperty("pmPriorityFocus").isEqualTo("onboarding"))
                            .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Focused current-roster membership alignment after the shared foundation exists."))
                            .to("tack:thought-enter?name=pm-onboarding-focus&maxTokens=250")
                            .to("direct:pm-team-onboarding")
                            .to("tack:thought-exit?name=pm-onboarding-focus")
                        .when(exchangeProperty("pmPriorityFocus").isEqualTo("coordination"))
                            .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Focused Project Manager team coordination for one fresh actionable exchange."))
                            .to("tack:thought-enter?name=pm-coordination-focus&maxTokens=250")
                            .to("direct:pm-team-sync")
                            .to("tack:thought-exit?name=pm-coordination-focus")
                        .when(exchangeProperty("pmPriorityFocus").isEqualTo("documentation"))
                            .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Focused durable workspace/navigation curation; current work state remains in Plane."))
                            .to("tack:thought-enter?name=pm-documentation-focus&maxTokens=250")
                            .to("direct:pm-documentation-status-sync")
                            .to("tack:thought-exit?name=pm-documentation-focus")
                        .when(exchangeProperty("pmPriorityFocus").isEqualTo("completion"))
                            .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Focused Project Manager mission completion evaluation using minimal authoritative evidence."))
                            .to("tack:thought-enter?name=pm-completion-focus&maxTokens=300")
                            .to("direct:pm-completion-focus")
                            .to("tack:thought-exit?name=pm-completion-focus")
                        .otherwise()
                            .delay(120000)
                    .end()
                .end()
                .end()
                .to("direct:pm-project-manager-root-exit");

        from("direct:pm-orientation")
                .routeId("pm-orientation")
                .setHeader(TackHeaders.PROMPT, constant("Read the smallest fresh current-mission slice needed to rank PM's next focus: shared basics, one actionable active-delivery/review/integration/blocker/topology/promotion signal, usable product work plus executable Developer Todo backlog depth, membership alignment, one fresh coordination signal, concrete docs/navigation drift, and completion plausibility. Do not mutate or solve the selected focus here."))
                .to("tack:act?id=pm-orientation&toolProfile=" + WorkspaceReadonlyToolProfile.ID);

        from("direct:pm-bootstrap-focus-dispatch")
                .routeId("pm-bootstrap-focus-dispatch")
                .to("direct:pm-project-basics-check")
                .choice()
                    .when(exchangeProperty("decision.collaboration-setup-complete.result").isNotEqualTo(true))
                        .to("direct:pm-project-bootstrap")
                .end();

        from("direct:pm-active-delivery-focus")
                .routeId("pm-active-delivery-focus")
                .setProperty("pmFocusedDeliveryIterations", constant(0))
                .setProperty("pmFocusedDeliveryContinue", constant(true))
                .loopDoWhile(simple("${exchangeProperty.pmFocusedDeliveryContinue} == true && ${exchangeProperty.pmFocusedDeliveryIterations} < 6"))
                    .setProperty("pmFocusedDeliveryIterations", simple("${exchangeProperty.pmFocusedDeliveryIterations++}"))
                    .setProperty("pmFocusedDeliveryContinue", constant(false))
                    .to("direct:pm-active-delivery-check")
                    .choice()
                        .when(exchangeProperty("act.pm-active-delivery-update.durableProgress").isEqualTo(true))
                            .setProperty("pmFocusedDeliveryContinue", constant(true))
                    .end()
                .end();

        from("direct:pm-planning-focus")
                .routeId("pm-planning-focus")
                .setProperty("pmFocusedPlanningIterations", constant(0))
                .setProperty("pmFocusedPlanningContinue", constant(true))
                .setProperty("decision.pm-dev-backlog-deep-enough.result", constant(false))
                .loopDoWhile(simple("${exchangeProperty.pmFocusedPlanningContinue} == true && ${exchangeProperty.pmFocusedPlanningIterations} < 5"))
                    .setProperty("pmFocusedPlanningIterations", simple("${exchangeProperty.pmFocusedPlanningIterations++}"))
                    .setProperty("pmFocusedPlanningContinue", constant(false))
                    .to("direct:pm-work-intake")
                    .choice()
                        .when(exchangeProperty("decision.pm-has-product-for-delivery.result").isEqualTo(true))
                            .to("direct:pm-task-planning-loop")
                            .choice()
                                .when(exchangeProperty("pmTaskPlanningMadeProgress").isEqualTo(true))
                                    .setProperty("pmFocusedPlanningContinue", constant(true))
                            .end()
                    .end()
                    .to("direct:pm-dev-backlog-depth-check")
                    .choice()
                        .when(exchangeProperty("decision.pm-dev-backlog-deep-enough.result").isEqualTo(true))
                            .setProperty("pmFocusedPlanningContinue", constant(false))
                    .end()
                .end();

        from("direct:pm-dev-backlog-depth-check")
                .routeId("pm-dev-backlog-depth-check")
                .setHeader(TackHeaders.PROMPT, constant("Read only the current assigned executable Developer implementation backlog in Plane. Count Todo children that are assigned, dependency-satisfied, non-cancelled, and sufficiently actionable from description/acceptance linkage. Note whether additional usable product parents are available for safe decomposition. Do not mutate or inspect source."))
                .to("tack:act?id=pm-dev-backlog-depth-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("Is the executable assigned Developer backlog deep enough without speculative over-planning? Return true when several successive actionable Todo implementation units are ready, normally about 3-5, or when product/dependency constraints make further safe planning inappropriate. Return false when useful product work is available but the executable Todo queue is still too shallow."))
                .to("tack:decide?id=pm-dev-backlog-deep-enough");

        from("direct:pm-completion-focus")
                .routeId("pm-completion-focus")
                .setHeader(TackHeaders.PROMPT, constant("Review current mission completion once using minimal authoritative evidence. One unfinished parent, child, current unresolved blocker, review, merge, product question, topology finding, or acceptance follow-up means incomplete. Superseded historical handoff comments are not current blockers. Do not mutate."))
                .to("tack:act?id=pm-mission-status-review&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("Is the mission complete from Project Management's perspective? Return true only when all required product parents are Done and no current unresolved delivery, blocker, PRODUCT QUESTION, review, integration, assignment, topology investigation/finding, or acceptance follow-up remains. Ignore historical handoffs superseded by newer disposition evidence."))
                .to("tack:decide?id=project-manager-mission-complete");

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
                        membership everywhere; pm-team-onboarding owns roster membership alignment after the foundation exists. Do not mutate.
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
                        Waiting handoffs are not actionable merely because they still exist; skip an unchanged item already waiting on another owner and continue to the next priority.
                        Priority: (1) In Progress TOPOLOGY INVESTIGATION with current TECHNICAL FINDING and no later PM disposition; (2) Done TOPOLOGY INVESTIGATION whose finding affects unfinished product and lacks later disposition; (3) REVIEW READY child with no later PM disposition; (4) INTEGRATION READY child whose PR merged or approval became stale/closed/conflicted/changed, skipping a clean open IR merely waiting on Director when other actionable PM work exists; (5) actionable BLOCKED child when PM can act now or must route/normalize a product/external blocker; (6) Backlog child whose dependencies may now be Done or invalid; (7) Cancelled child whose required coverage remains or is still a dependency; (8) In Progress product parent whose children may now be terminally dispositioned and required Done coverage complete. Do not mutate in this read.
                        """))
                .to("tack:act?id=pm-active-delivery-evidence-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Update only the item already read. Preserve current delivery semantics: accept/disposition topology findings; close verified merged IR children; issue precise REVIEW FEEDBACK for stale/conflicted/closed-unmerged/changed IR; convert acceptable REVIEW READY to a current INTEGRATION READY lease pinned to reviewedHead, reviewedBaseRef, reviewedBaseSha, reviewedAt, validation and acceptanceRef; promote dependency-satisfied Backlog children to Todo; route/repair current blockers and PRODUCT QUESTION returns; repair required cancelled coverage/dependency edges; and publish PRODUCT ACCEPTANCE READY only when all required children/topology/question/acceptance ordering conditions are satisfied. Do not create activity for unchanged handoffs already waiting on their owner.
                        """))
                .to("tack:act?id=pm-active-delivery-update&toolProfile=" + ProjectManagementToolProfile.ID)
                .to("tack:thought-exit?name=pm-active-delivery");

        from("direct:pm-work-intake")
                .routeId("pm-work-intake")
                .setProperty("decision.pm-has-product-for-delivery.result", constant(false))
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("One product parent for delivery planning, topology recovery, or product-handoff validation."))
                .to("tack:thought-enter?name=pm-intake&maxTokens=250")
                .setHeader(TackHeaders.PROMPT, constant("Select at most one PRODUCT PARENT. Prefer Todo initial PO handoff and validate canonical acceptance. Otherwise select qualifying In Progress parents needing decomposition, cancelled-coverage recovery, dependency repair, acceptance-feedback remediation, topology recovery, or reevaluation after a newer PRODUCT HANDOFF. Raise/refresh PRODUCT QUESTION when product judgment or usable acceptance is genuinely missing. Never invent product scope or implement the parent."))
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
                        Freshly read canonical product acceptance and existing children. Build a coverage map and the reachable transitive dependency graph. Create or refine exactly one missing/remediation/replacement/oversized child, invalid dependency edge, terminal disposition, or topology-recovery frontier per pass. Every required behavior needs non-duplicative non-cancelled coverage. Cancelled work provides zero coverage. Keep the dependency graph viable: no self or child-to-own-parent dependency, no cycles including cross-product cycles, no unfinished dependency on Cancelled work, and every unfinished component needs a viable Todo/In Progress/BLOCKED frontier. If technical topology cannot safely be inferred, create exactly one dependency-free Todo TOPOLOGY INVESTIGATION assigned to Developer for the exact ambiguity rather than guessing. Each normal implementation child must be linked to parent, acceptance behaviors, one Developer assignee, priority, repo, dependencies/independence, next step, expected review evidence, and Todo if executable or Backlog only when genuinely dependency-blocked. Move initial Todo parent -> In Progress when taking delivery.
                        """))
                    .to("tack:act?id=pm-actionable-dev-task&toolProfile=" + ProjectManagementToolProfile.ID)
                    .choice().when(exchangeProperty("act.pm-actionable-dev-task.durableProgress").isEqualTo(true)).setProperty("pmTaskPlanningMadeProgress", constant(true)).setProperty("pmTaskPlanningPassProgress", constant(true)).end()
                    .setHeader(TackHeaders.PROMPT, constant("Is delivery planning sufficiently routed for now? Return true when required acceptance has non-cancelled coverage, children are independently finishable/terminally dispositioned, the reachable dependency graph is viable, executable children are assigned Todo, current feedback has remediation, no unresolved product question blocks intent, and topology findings are dispositioned; or when the only remaining topology ambiguity is represented by exactly one current actionable TOPOLOGY INVESTIGATION. Otherwise return false."))
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
