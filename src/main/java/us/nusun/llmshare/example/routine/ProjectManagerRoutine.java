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
                .main("Own project management for the whole mission through completion: establish the shared workspace, keep every current teammate onboarded to the shared project/channel, consume Product Owner handoffs, keep a useful implementation backlog ahead of development, coordinate blockers/dependencies, and return completed outcomes for product acceptance.", "direct:example-software-team-project-manager")
                .subroutine("bootstrap", "Establish only the shared Plane, source-control, documentation, and team-chat foundation required for delivery.", "direct:example-software-team-project-manager-bootstrap")
                .subroutine("coordination", "Operate an established delivery workspace through roster onboarding, decomposition, blockers, dependencies, merged-work reconciliation, and product acceptance.", "direct:example-software-team-project-manager-coordination")
                .subroutine("task-planning", "Decompose one Todo or active product parent into discrete implementation children whose combined coverage satisfies product acceptance while keeping useful work queued ahead of the Developer.", "direct:example-software-team-project-manager-task-planning")
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
                            Operating model: PO stays ahead of PM with several ready product slices; PM stays ahead of DEV with several implementation children; DEV normally carries only one active/unmerged source unit. Developer WIP=1 does NOT mean planning WIP=1.

                            ACCESS/LIVENESS INVARIANT: every teammate in the CURRENT MISSION ROSTER must be able to use the current-mission Plane project and mission team-chat channel before PM relies on that teammate for product, planning, development, review, or coordination. A newly started PO or DEV who is not yet a Plane project member or chat-channel member is an immediate onboarding condition. Do not wait for that teammate to report an access failure. Do not infer membership from an older roster, workspace-account existence, assignments, comments, or prior onboarding.

                            Allowed focus values, in priority order when genuinely actionable:
                            - bootstrap: any shared Plane/source/docs/chat foundation basic is missing or unusable.
                            - onboarding: the foundation exists and any CURRENT MISSION ROSTER teammate lacks verified Plane project membership or mission-channel membership, including teammates started after bootstrap or access drift. This outranks delivery/planning because inaccessible teammates can deadlock the pipeline.
                            - active-delivery: a merged/open-PR reconciliation, topology finding, blocker, dependency promotion/repair, cancelled coverage, product-question return, or completed-parent handoff can be acted on now. PM does not issue a magic approval lease before Director integration.
                            - planning: usable product work exists and the assigned executable Developer Todo backlog is below a useful buffer, normally about 3-5 actionable children, or future dependency-blocked implementation work should be represented in Backlog. An active Developer task or open PR is NOT a reason to stop planning.
                            - coordination: there is a useful message to initiate, or a fresh team exchange needs Project Manager participation.
                            - documentation: a concrete durable navigation/status curation need exists; daily work state belongs in Plane.
                            - completion: durable evidence makes mission completion plausible enough for the dedicated completion check.
                            - wait: no focus above is currently useful. Never wait while a current teammate lacks verified project/channel access. A shallow Developer backlog is actionable planning work, not a reason to wait.

                            Fresh orientation evidence outranks older HOLD/wait conclusions. The CURRENT MISSION ROSTER is authoritative for who needs access now. Pick one focus only and return only the focus object.
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
                        .when(exchangeProperty("pmPriorityFocus").isEqualTo("onboarding"))
                            .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Focused current-roster access alignment: ensure every currently running teammate has verified Plane project and mission-channel membership before delivery depends on them."))
                            .to("tack:thought-enter?name=pm-onboarding-focus&maxTokens=250")
                            .to("direct:pm-team-onboarding")
                            .to("tack:thought-exit?name=pm-onboarding-focus")
                        .when(exchangeProperty("pmPriorityFocus").isEqualTo("active-delivery"))
                            .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Focused delivery supervision: reconcile one current blocker, topology/dependency issue, merged/open-PR consequence, product return, or parent-delivery outcome while useful dispositions remain."))
                            .to("tack:thought-enter?name=pm-active-delivery-focus&maxTokens=400")
                            .to("direct:pm-active-delivery-focus")
                            .to("tack:thought-exit?name=pm-active-delivery-focus")
                        .when(exchangeProperty("pmPriorityFocus").isEqualTo("planning"))
                            .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Focused delivery planning: create/refine one implementation child per act, repeating until the executable Developer Todo buffer is normally about 3-5 or safe planning genuinely cannot continue. Dependency-blocked future children belong in Backlog rather than being omitted."))
                            .to("tack:thought-enter?name=pm-planning-focus&maxTokens=400")
                            .to("direct:pm-planning-focus")
                            .to("tack:thought-exit?name=pm-planning-focus")
                        .when(exchangeProperty("pmPriorityFocus").isEqualTo("coordination"))
                            .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Focused Project Manager coordination for one useful initiated notification/question/handoff or one fresh actionable exchange."))
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
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read the smallest fresh current-mission slice needed to rank PM's next focus. Membership liveness is mandatory: compare the CURRENT MISSION ROSTER with current Plane project membership and mission team-chat channel membership whenever the foundation exists or a new teammate has appeared since the last verified roster. Distinguish mission roster, Plane workspace membership, Plane project membership, and chat-channel membership. A roster teammate is NOT onboarded until project and channel membership are verified. Do not infer this from assignments, comments, an older roster, or the mere existence of a Plane workspace account.

                        Also capture shared basics; one actionable blocker/topology/dependency/merged-work/product-return signal; usable product work; assigned executable Developer Todo depth; useful dependency-blocked future work; one useful message worth initiating or fresh coordination signal; concrete docs/navigation drift; and completion plausibility. If thought already contains verified project/repo/channel ids, use direct lookups instead of listing whole workspaces. Report current observations only. Do not recommend a next focus, invent sequencing policy, preserve an older HOLD conclusion against newer evidence, mutate, or solve the selected focus here.
                        """))
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
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read only current implementation-child queue depth in Plane. Count assigned, dependency-satisfied, actionable Todo children for Developer. Also note useful planned children in Backlog whose only blocker is an explicit unfinished dependency, and whether more usable product parents can safely be decomposed. Developer active WIP/open PR does not reduce desired planning depth. Do not mutate or inspect source.
                        """))
                .to("tack:act?id=pm-dev-backlog-depth-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Is PM sufficiently ahead of Developer for now? Normally require about 3-5 assigned, dependency-satisfied actionable Todo implementation children when enough safe product work exists. Return false when useful product work can be decomposed but that executable queue is shallower, even if Developer already has active WIP or an open PR. Explicit dependency-blocked future children in Backlog are healthy planning, but they do not count as executable Todo depth. Return true with a shallower queue only when product information, genuine dependency topology, or remaining mission size makes further safe executable planning inappropriate.
                        """))
                .to("tack:decide?id=pm-dev-backlog-deep-enough");

        from("direct:pm-completion-focus")
                .routeId("pm-completion-focus")
                .setHeader(TackHeaders.PROMPT, constant("Review current mission completion once using minimal authoritative evidence. One unfinished parent, child, current unresolved blocker, merge, product question, topology finding, acceptance follow-up, or current-roster access gap means incomplete. Comments are evidence/context, never hidden workflow state. Do not mutate."))
                .to("tack:act?id=pm-mission-status-review&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("Is the mission complete from Project Management's perspective? Return true only when all required product parents are Done, every current-roster teammate has required project/channel access, and no current unresolved delivery, blocker, PRODUCT QUESTION, integration, assignment, topology investigation/finding, or acceptance follow-up remains. Ignore historical comments superseded by newer durable state/evidence."))
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
                        .to("direct:pm-team-onboarding")
                        .to("direct:pm-coordination-loop")
                .end()
                .to("direct:pm-project-manager-root-exit");

        from("direct:example-software-team-project-manager-task-planning")
                .routeId("example-software-team-project-manager-task-planning")
                .to("direct:pm-project-manager-root-enter")
                .to("direct:pm-project-basics-check")
                .choice()
                    .when(exchangeProperty("decision.collaboration-setup-complete.result").isEqualTo(true))
                        .to("direct:pm-team-onboarding")
                        .to("direct:pm-work-intake")
                        .choice()
                            .when(exchangeProperty("decision.pm-has-product-for-delivery.result").isEqualTo(true))
                                .to("direct:pm-task-planning-loop")
                        .end()
                .end()
                .to("direct:pm-project-manager-root-exit");

        from("direct:pm-project-manager-root-enter")
                .routeId("pm-project-manager-root-enter")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Durable delivery anchors: verified project/repo/channel ids, current-roster access alignment, selected product parent, implementation queue depth/coverage, product questions, dependency topology, source/PR evidence, blockers, merged-work reconciliation, and team chat."))
                .to("tack:thought-enter?name=project-manager-routine&maxTokens=500");

        from("direct:pm-project-manager-root-exit")
                .routeId("pm-project-manager-root-exit")
                .to("tack:thought-exit?name=project-manager-routine");

        from("direct:pm-project-basics-check")
                .routeId("pm-project-basics-check")
                .setProperty("decision.collaboration-setup-complete.result", constant(false))
                .setHeader(TackHeaders.PROMPT, constant("""
                        Verify only the shared foundation needed to enter coordination: exactly one current-mission Plane project, documentation repository/navigation, source repository under agent-workspace, and mission team-chat channel, all accessible to this Project Manager run. Prefer direct lookup by verified ids already in thought.

                        Foundation existence and teammate onboarding are separate. Do not call a teammate onboarded merely because the project/channel exists or because their Plane workspace account exists. During bootstrap, add every teammate already present in the CURRENT MISSION ROSTER. Future teammates are expected to be reconciled immediately by the reusable onboarding focus when they appear in the current roster.
                        Do not mutate.
                        """))
                .to("tack:act?id=pm-workspace-basics-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("Are Plane, source repo, docs/navigation, and mission chat verified and readable by this Project Manager run? Return true based on foundation existence/readability only; current-roster teammate access is handled separately and must not be assumed from this result."))
                .to("tack:decide?id=collaboration-setup-complete");

        from("direct:pm-project-bootstrap")
                .routeId("pm-project-bootstrap")
                .setProperty("collaborationSetupAttempt", constant(0))
                .setProperty("pmBootstrapMadeProgress", constant(true))
                .loopDoWhile(simple("${exchangeProperty.decision.collaboration-setup-complete.result} != true && ${exchangeProperty.pmBootstrapMadeProgress} == true && ${exchangeProperty.collaborationSetupAttempt} < 6"))
                    .setProperty("collaborationSetupAttempt", simple("${exchangeProperty.collaborationSetupAttempt++}"))
                    .setProperty("pmBootstrapMadeProgress", constant(false))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Bootstrap only: Plane project, source/docs repositories, mission chat, navigation, immediate access for current-roster teammates, and one useful kickoff notification. Product work is not a bootstrap prerequisite."))
                    .to("tack:thought-enter?name=pm-bootstrap&maxTokens=300")
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Create or confirm exactly one current-mission Plane project. Preserve an existing useful project. Then reconcile project membership against the CURRENT MISSION ROSTER using actual Plane evidence: for each current-roster Plane identity, resolve the matching Plane workspace member, compare it with the project's current member list, and add the resolved teammate when missing. Keep mission roster, workspace membership, and project membership as separate sets. Do not infer that a teammate is absent from the Plane workspace because they were absent from an earlier roster. Report only verified membership facts. Do not add hypothetical/future personas, remove nobody, and do not create product or implementation work.
                            """))
                    .to("tack:act?id=plane-project-provisioning&toolProfile=" + WorkspaceCoordinationToolProfile.ID)
                    .choice().when(exchangeProperty("act.plane-project-provisioning.durableProgress").isEqualTo(true)).setProperty("pmBootstrapMadeProgress", constant(true)).end()
                    .setHeader(TackHeaders.PROMPT, constant("Create or confirm exactly one markdown documentation repository under agent-workspace. Do not create planned work in source control."))
                    .to("tack:act?id=documentation-repository-provisioning&toolProfile=" + WorkspaceCoordinationToolProfile.ID)
                    .choice().when(exchangeProperty("act.documentation-repository-provisioning.durableProgress").isEqualTo(true)).setProperty("pmBootstrapMadeProgress", constant(true)).end()
                    .setHeader(TackHeaders.PROMPT, constant("Create or confirm exactly one target source repository under agent-workspace."))
                    .to("tack:act?id=source-repository-provisioning&toolProfile=" + WorkspaceCoordinationToolProfile.ID)
                    .choice().when(exchangeProperty("act.source-repository-provisioning.durableProgress").isEqualTo(true)).setProperty("pmBootstrapMadeProgress", constant(true)).end()
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Create or confirm one private current-mission team-chat channel accessible to Project Management. Never create a duplicate channel. Reconcile that channel against the CURRENT MISSION ROSTER using actual current channel membership and add every current teammate who is missing when supported. Do not infer channel membership from Plane membership or from an older roster. Do not add hypothetical/future personas and remove nobody. After the channel is usable, initiate one concise kickoff message linking the authoritative Plane project and source/docs repositories known so far and telling current teammates the workspace is ready for their role. Do not wait for someone else to speak first. Later roster changes or access drift remain the reusable onboarding focus.
                            """))
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
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Current delivery pass: one actionable blocker/dependency/topology/merged-work/product-return item, then one product parent needing decomposition. PM planning should remain ahead of Developer execution."))
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
                .setHeader(TackHeaders.PROMPT, constant("""
                        Reconcile CURRENT MISSION ROSTER access now. This is a liveness requirement, not optional housekeeping.

                        Plane:
                        1. Read the current mission project members.
                        2. For EACH teammate in the CURRENT MISSION ROSTER, resolve that teammate's Plane identity against Plane workspace members.
                        3. Add every resolved current-roster teammate who is missing from the project.

                        Team chat:
                        4. Read current membership of the known mission channel.
                        5. Resolve each CURRENT MISSION ROSTER teammate's Mattermost identity and add every missing current teammate when supported.

                        Keep these sets separate: active mission roster, Plane workspace members, Plane project members, Mattermost channel members. Never infer one from another or from an older thought. A teammate added after bootstrap must be onboarded even if bootstrap was previously complete. Do not stop after discovering the account exists; verify project/channel membership and add it when missing. If a current-roster identity cannot be resolved, report that exact unresolved identity as a blocker. Remove nobody. After adding a teammate, send one concise notification with authoritative project/repo/channel anchors when useful.
                        """))
                .to("tack:act?id=pm-team-onboarding&toolProfile=" + WorkspaceCoordinationToolProfile.ID);

        from("direct:pm-active-delivery-check")
                .routeId("pm-active-delivery-check")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("One delivery-maintenance item: topology finding/application, merged/open-PR reconciliation, actionable blocker/product question, dependency promotion/repair, cancelled coverage, or completed-parent handoff. Comments provide evidence/context, not workflow state."))
                .to("tack:thought-enter?name=pm-active-delivery&maxTokens=300")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read at most one highest-value ACTIONABLE current delivery item using fresh Plane and linked source evidence. Priority: (1) current topology finding needing PM disposition; (2) implementation child whose linked PR has MERGED but Plane child is not yet Done; (3) actionable BLOCKED child when PM can route/repair it; (4) Backlog child whose explicit dependencies may now be satisfied or invalid; (5) Cancelled child whose required coverage remains or is still a dependency; (6) In Progress product parent whose implementation children may now be terminally dispositioned and required Done coverage complete; (7) an open-PR implementation child only when PM has a concrete blocker/dependency/product-context issue to repair. Director owns normal safe PR integration directly; PM does not create an INTEGRATION READY approval lease. Do not mutate in this read.
                        """))
                .to("tack:act?id=pm-active-delivery-evidence-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Update only the item already read. Use Plane real states and concise evidence/context comments; never manufacture REVIEW READY, INTEGRATION READY, PRODUCT ACCEPTANCE READY, or similar hidden workflow states. For a verified merged implementation PR, move that implementation child In Progress -> Done and retain concise merge/validation evidence. Promote dependency-satisfied Backlog children to Todo. Route/repair current blockers and PRODUCT QUESTION returns. Repair required cancelled coverage/dependency edges. When an In Progress product parent's required implementation children are Done and product acceptance can be evaluated, leave concise evidence/context for Product Owner and let the real Plane states plus durable completion evidence make the parent actionable. Do not create activity for unchanged work already waiting on another owner.
                        """))
                .to("tack:act?id=pm-active-delivery-update&toolProfile=" + ProjectManagementToolProfile.ID)
                .to("tack:thought-exit?name=pm-active-delivery");

        from("direct:pm-work-intake")
                .routeId("pm-work-intake")
                .setProperty("decision.pm-has-product-for-delivery.result", constant(false))
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("One product parent for delivery planning, topology recovery, product-question handling, or coverage/dependency repair."))
                .to("tack:thought-enter?name=pm-intake&maxTokens=250")
                .setHeader(TackHeaders.PROMPT, constant("Select at most one PRODUCT PARENT. Prefer Todo initial PO handoff and validate canonical acceptance. Otherwise select qualifying In Progress parents needing additional decomposition, cancelled-coverage recovery, dependency repair, acceptance-feedback remediation, topology recovery, or reevaluation after clarified product context. An active implementation child/open PR does not make a parent ineligible for further safe planning. Raise/refresh PRODUCT QUESTION only when product judgment or usable acceptance is genuinely missing. Never invent product scope or implement the parent."))
                .to("tack:act?id=pm-intake-review&toolProfile=" + ProjectManagementToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("Is one Todo or qualifying In Progress product parent selected with readable usable acceptance and a real decomposition/replanning/topology/coverage-recovery need? Return false when a PRODUCT QUESTION had to be raised and remains unresolved."))
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
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Second-stage decomposition: current acceptance, existing non-cancelled coverage, dependency-graph viability, assignees, and exactly one missing/remediation implementation outcome this pass. One-child-per-pass is mutation size, not total planning depth."))
                    .to("tack:thought-enter?name=pm-planning&maxTokens=300")
                    .setHeader(TackHeaders.PROMPT, constant("""
                        Freshly read canonical product acceptance and existing children. Build a coverage map and reachable dependency graph. Create or refine exactly ONE missing/remediation/replacement/oversized child or dependency/topology repair per pass. This one-child limit controls mutation size only; it does NOT mean only one child may exist or that PM should wait for current Developer work to merge before planning more. Continue successive planning passes while useful uncovered acceptance or shallow queue depth remains.

                        Every required behavior needs non-duplicative non-cancelled coverage. Keep the dependency graph viable: no self/parent cycles, no unfinished dependency on Cancelled work, and every unfinished component needs a viable frontier. If a future implementation child depends on unfinished work, CREATE IT NOW in Backlog with the explicit dependency instead of omitting it. If dependencies are already satisfied, make it assigned Todo. A Developer may have one active/unmerged source unit while PM maintains several future children. If technical topology genuinely cannot be inferred, create exactly one dependency-free Todo TOPOLOGY INVESTIGATION for the exact ambiguity rather than guessing.

                        Each normal implementation child must link parent acceptance behavior, one Developer assignee, priority, repo, dependencies/independence, next step, and expected validation/review evidence. Move initial Todo parent -> In Progress when taking delivery. Do not encode workflow state in comments.
                        """))
                    .to("tack:act?id=pm-actionable-dev-task&toolProfile=" + ProjectManagementToolProfile.ID)
                    .choice().when(exchangeProperty("act.pm-actionable-dev-task.durableProgress").isEqualTo(true)).setProperty("pmTaskPlanningMadeProgress", constant(true)).setProperty("pmTaskPlanningPassProgress", constant(true)).end()
                    .setHeader(TackHeaders.PROMPT, constant("""
                        Is THIS PRODUCT PARENT sufficiently decomposed for now? Return true when its required acceptance has non-cancelled implementation coverage, dependency graph is viable, dependency-satisfied children are assigned Todo, dependency-blocked future children are represented in Backlog with explicit edges, current feedback has remediation, no unresolved product question blocks intent, and topology findings are dispositioned. Do not return true merely because one child is currently active or has an open PR. Otherwise return false.
                        """))
                    .to("tack:decide?id=pm-task-planning-done")
                    .choice().when(exchangeProperty("decision.pm-task-planning-done.result").isEqualTo(true)).setProperty("pmTaskPlanningPassProgress", constant(false)).end()
                    .to("tack:thought-exit?name=pm-planning")
                .end();

        from("direct:pm-team-sync")
                .routeId("pm-team-sync")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read only recent project chat needed for one useful exchange. Do not require an existing conversation before posting. Initiate one concise message when a workspace-ready notification, new teammate onboarding, product-to-delivery handoff, dependency/blocker question, merged-work notice, or other meaningful event would help a current teammate act sooner. Otherwise respond to one fresh useful exchange. Avoid generic status. Plane remains authoritative for assignments/blockers/state and Gitea for implementation/PR state.
                        """))
                .to("tack:act?id=pm-team-chat-sync&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:pm-documentation-status-sync")
                .routeId("pm-documentation-status-sync")
                .setHeader(TackHeaders.PROMPT, constant("Read only current mission delivery/navigation anchors when needed. Prefer known ids and direct files. Keep daily work state in Plane."))
                .to("tack:act?id=pm-workspace-status-read&toolProfile=" + SourceControlToolProfile.ID);
    }
}
