package us.nusun.llmshare.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.example.tool.DeliveryManagementToolProfile;
import us.nusun.llmshare.example.tool.ProjectDocumentationToolProfile;
import us.nusun.llmshare.example.tool.TeamChatToolProfile;
import us.nusun.llmshare.example.tool.WorkspaceCoordinationToolProfile;
import us.nusun.llmshare.example.tool.WorkspaceReadonlyToolProfile;
import us.nusun.llmshare.framework.inventory.RoutineProvider;
import us.nusun.llmshare.framework.model.LlmPassType;
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
                .main("Manage delivery flow, synchronization, and ready-work buffers across the team.",
                        "direct:example-software-team-project-manager")
                .subroutine("bootstrap", "Establish shared coordination basics.", "direct:pm-bootstrap")
                .subroutine("planning", "Prepare active and ready Epics into executable Story work.", "direct:pm-planning")
                .subroutine("documentation", "Keep canonical project basics current in the system repository.",
                        "direct:pm-project-basics")
                .subroutine("flow", "Synchronize active delivery, defects, and handoffs.", "direct:pm-flow")
                .defaultLane("high-pass")
                .withLaneBinding(LlmPassType.SYNTHESIS, "low-pass")
                .withLaneBinding(LlmPassType.CONSOLIDATION, "low-pass")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-software-team-project-manager")
                .routeId("example-software-team-project-manager")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Durable flow anchors: Plane project, shared team channel, active roster, verified access parity,
                        system repo and canonical project-basics documentation, active/ready epic sets,
                        implementation/test queues, defects, dependencies, PR consequences, blockers, and handoffs. PM's
                        primary operational responsibility is keeping Plane coherent, assigned, and executable.
                        """))
                .to("tack:thought-enter?name=pm-root&maxTokens=450")
                .setProperty("decision.project-manager-mission-complete.result", constant("no"))
                .loopDoWhile(exchangeProperty("decision.project-manager-mission-complete.result").isNotEqualTo("yes"))
                    .to("direct:pm-orientation")
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Choose exactly one highest-value Project Manager focus.
                            - bootstrap: shared Plane project, canonical lifecycle states, or team-channel basics are
                              missing.
                            - onboarding: any current active roster identity is missing from the Plane project or the
                              shared mission channel, or either membership set cannot be verified against the active roster.
                              This is the highest-priority focus and takes precedence over bootstrap follow-up, delivery,
                              planning, documentation, defect flow, and coordination.
                            - defect-flow: a PO-validated Todo defect needs repair assignment, or an In Progress
                              Defect with a merged fix needs QA awareness for retest.
                            - active-flow: any verified non-Backlog work item lacks an appropriate owner; or an active
                              DEV/ARCH has queue capacity and a dependency-satisfied Backlog story is
                              available; or dependency repair, a cyclic Story dependency graph, obsolete Story cleanup,
                              stale assignment, engineer-owned Story completion awareness, blocker routing, QA handoff, or
                              another active-Epic synchronization issue is actionable.
                              Ownership repair outranks filling additional queue capacity. Capacity is per engineer:
                              ARCH work never
                              consumes DEV capacity, and DEV work never consumes ARCH capacity. Feeding existing executable
                              work outranks creating more Backlog.
                            - planning: repository architecture is established and any In Progress or Todo Epic lacks the
                              reasonable story/dependency set needed to keep active delivery supplied
                              and ready product work pre-decomposed. Planning spans all active and ready Epics. If the
                              repository map is not established, route that architecture dependency instead of inventing
                              implementation topology.
                            - documentation: Architect has established the system repository and docs/project-basics.md is
                              missing or materially stale for mission, Plane project, shared channel, repo map, or roles.
                            - coordination: a fresh delivery question needs PM participation.
                            - complete: every required Epic is terminal, no Story/defect/test delivery remains active,
                              and no PM synchronization/reconciliation blocker remains.
                            - wait: the mission is not complete, onboarding is healthy, no dependency/state/assignment
                              repair is pending, every delivery role has a healthy executable runway or no legitimate
                              candidate exists across any In Progress Epic, ready Todo Epics are sufficiently decomposed,
                              and none of the other focuses is useful now.
                            After onboarding, durable Plane delivery mutations outrank documentation, coordination, and
                            waiting. Keep active delivery shallow while preparing enough work ahead of execution.
                            Choose exactly one value.
                            """))
                    .to("tack:decide?id=pm-focus&choices=bootstrap,onboarding,documentation,defect-flow,"
                            + "active-flow,planning,coordination,complete,wait")
                    .toD("direct:pm-focus-${exchangeProperty.decision.pm-focus.result}")
                .end()
                .end()
                .to("tack:thought-exit?name=pm-root");

        from("direct:pm-orientation")
                .routeId("pm-orientation")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read the smallest current-mission slice needed to rank PM focus. Reuse verified project, lifecycle
                        state, label, repository, and channel anchors already in thought instead of rediscovering them.
                        First, directly observe onboarding
                        status from current state: read the authoritative active roster, actual Plane project membership,
                        and actual shared mission-channel membership. Compare the active identities against both membership
                        sets and explicitly report whether they are in sync, plus every missing active identity in either
                        system. Resolve current-roster Plane/Mattermost identities only when needed for that comparison.
                        Do not use prior thought or a previously reconciled roster to establish onboarding status.
                        A current active teammate is operationally onboarded only when present in both current membership
                        sets. This onboarding observation is mandatory before ranking any other focus.
                        Also capture whether Plane has the canonical lifecycle states Backlog, Todo, In Progress, Done,
                        and Cancelled. Missing canonical states make bootstrap incomplete. Once onboarding is healthy,
                        treat Plane delivery health as the primary observation: repository/component readiness; current
                        docs/project-basics.md; all epic items currently In Progress plus the ready Todo Epic set and
                        their PO-authored Given/When/Then acceptance; relevant story children;
                        test children; defect items; Story coverage, assignments,
                        lifecycle, and dependencies. Explicitly report every Todo/In Progress/Done/Cancelled item with no
                        assignee or an assignee inconsistent with verified classification. For each DEV/ARCH delivery
                        identity, report assigned In Progress count, assigned Todo count, and up to three
                        dependency-satisfied Backlog Story candidates beneath In Progress Epics. Linked PR state only when
                        needed; one blocker/handoff;
                        and one fresh exchange from the shared mission channel. Do not use unrelated or long-lived DM
                        history as mission evidence. PO owns epic lifecycle; QA owns test lifecycle. Report only.
                        """))
                .to("tack:act?id=pm-orientation&toolProfile=" + WorkspaceReadonlyToolProfile.ID);

        from("direct:pm-focus-bootstrap")
                .routeId("pm-focus-bootstrap")
                .to("direct:pm-bootstrap");

        from("direct:pm-bootstrap")
                .routeId("pm-bootstrap")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Establish only the shared coordination foundation: one mission Plane project, its canonical
                        lifecycle states, and one private current-mission team channel. Reuse matching artifacts and never
                        create duplicates. After resolving the mission project, list its states and ensure Backlog, Todo,
                        In Progress, Done, and Cancelled exist. Create only missing states with the matching lifecycle
                        groups: Backlog=backlog, Todo=unstarted, In Progress=started, Done=completed,
                        Cancelled=cancelled. Re-read states and verify all five before bootstrap is complete.
                        Reconcile every current-roster identity into both Plane project and shared channel, then verify
                        both membership sets before treating bootstrap/onboarding as complete. Once the shared channel is usable,
                        post one concise kickoff message there identifying the Plane project and telling current teammates
                        the workspace is ready. Do not create source/docs repositories. Architect owns system and component
                        repository provisioning.
                        """))
                .to("tack:act?id=pm-bootstrap&toolProfile=" + WorkspaceCoordinationToolProfile.ID)
                .to("direct:pm-awareness");

        from("direct:pm-focus-onboarding")
                .routeId("pm-focus-onboarding")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Compare the authoritative current mission roster with actual Plane project and team-channel
                        membership. Add only resolved current-roster identities that are missing. Use the existing private
                        current-mission team channel for Mattermost access; do not substitute a direct channel for missing
                        mission-channel membership. Re-read both membership sets after additions and claim completion only
                        when every current active identity is verified in both. Team onboarding is a recurring PM
                        responsibility whenever the roster changes. Do not infer access from roster presence and do not
                        manage repository architecture.
                        """))
                .to("tack:act?id=pm-onboarding&toolProfile=" + WorkspaceCoordinationToolProfile.ID)
                .to("direct:pm-awareness");

        from("direct:pm-focus-documentation")
                .routeId("pm-focus-documentation")
                .to("direct:pm-project-basics");

        from("direct:pm-project-basics")
                .routeId("pm-project-basics")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Visit the Architect-established system repository documentation before writing. Read the current
                        README and relevant docs, especially docs/architecture.md and any existing docs/project-basics.md.
                        Create or update only docs/project-basics.md as compact canonical navigation for the mission:
                        mission purpose/anchor, Plane project, shared Mattermost channel, system/component repository map,
                        role responsibilities, and stable delivery conventions teammates need to find the work. Use exact
                        verified identifiers/links when available. Keep transient status, WIP, and speculative plans out.
                        Do not provision repositories, change architecture, edit component source, or duplicate durable
                        Plane state. Reuse an existing documentation branch/PR when present; otherwise create one concise
                        documentation PR for Architect integration.
                        """))
                .to("tack:act?id=pm-project-basics&toolProfile=" + ProjectDocumentationToolProfile.ID)
                .to("direct:pm-awareness");

        from("direct:pm-focus-defect-flow")
                .routeId("pm-focus-defect-flow")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Route one PO-validated defect without changing product judgment. A validated Backlog Defect
                        remains product backlog until PO moves it to Todo; do not assign or promote it. For a Todo Defect
                        with a verified Epic parent and product priority, choose the appropriate engineer from evidence:
                        ordinary component behavior -> DEV; system integration/repository/GitOps/cross-cutting architecture
                        -> ARCH. Assign it and leave it Todo for the engineer to claim. Do not create a repair Story.
                        For an In Progress Defect whose fix PR is verified merged, keep the Defect In Progress and surface
                        concise evidence to QA for retest; QA owns the final Done transition.
                        """))
                .to("tack:act?id=pm-defect-flow&toolProfile=" + DeliveryManagementToolProfile.ID)
                .to("direct:pm-awareness");

        from("direct:pm-focus-active-flow")
                .routeId("pm-focus-active-flow")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Focused delivery supervision across In Progress Epics: verified work types, ownership, executable
                        queue depth, dependency promotions, merged-work reconciliation, and concrete blockers/handoffs.
                        """))
                .to("tack:thought-enter?name=pm-active-flow-focus&maxTokens=500")
                .to("direct:pm-active-flow-loop")
                .to("tack:thought-exit?name=pm-active-flow-focus")
                .to("direct:pm-awareness");

        from("direct:pm-active-flow-loop")
                .routeId("pm-active-flow-loop")
                .setProperty("pmActiveFlowIterations", constant(0))
                .setProperty("pmActiveFlowContinue", constant(true))
                .loopDoWhile(simple("${exchangeProperty.pmActiveFlowContinue} == true && ${exchangeProperty.pmActiveFlowIterations} < 6"))
                    .setProperty("pmActiveFlowIterations", simple("${exchangeProperty.pmActiveFlowIterations++}"))
                    .setProperty("pmActiveFlowContinue", constant(false))
                    .to("direct:pm-flow")
                    .choice()
                        .when(exchangeProperty("act.pm-flow.durableProgress").isEqualTo(true))
                            .setProperty("pmActiveFlowContinue", constant(true))
                    .end()
                .end();

        from("direct:pm-flow")
                .routeId("pm-flow")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Restore delivery across the current In Progress Epic set to a coherent executable state in this pass.
                        Before any assignment or lifecycle mutation, verify current attached classification labels establish
                        the expected work kind. Plane type_id is unrelated to Tack classification. Unknown classification means do not mutate. Never treat epic or test as
                        engineer source work. Every non-Backlog work item must have an owner appropriate to its verified
                        classification: epic -> PO; story -> DEV/ARCH; test -> QA; defect -> repair engineer,
                        except a newly PO-validated Todo Defect may remain briefly unassigned until PM routes it. Repair a verified
                        non-Backlog ownership gap only within the role that owns that work. Do not guess classification
                        merely to fill an ownership gap. Apply the obvious state/assignment/dependency promotions needed
                        for healthy flow instead of stopping after one observation. When an assigned Story PR is merged
                        but the Story is still In Progress,
                        notify the assigned engineer to complete its lifecycle rather than closing it for them. When an
                        DEV/ARCH has queue capacity, promote enough dependency-satisfied Backlog Story work beneath any
                        In Progress Epic to assigned Todo to restore a healthy buffer of roughly 2-3 executable queued
                        units. Inspect up to three legitimate candidates instead of stopping after one promotion. This
                        applies even when that engineer's AgentRun is temporarily absent; durable assignment and readiness
                        must not track ephemeral runtime presence. Capacity is independent per engineer. A source unit
                        waiting only on PR integration or an external condition does not consume active coding capacity.
                        Do not leave executable Story work in Backlog merely because its intended engineer is not running.
                        Never infer a dependency from Story sequence, concurrent execution, or a failed relation-tool
                        payload. Story dependency relations must remain acyclic. If verified relations form a cycle that
                        prevents forward progress, remove the least-justified relation(s) needed to restore an executable
                        path; do not wait on a circular dependency. When current PO acceptance clearly makes a Story
                        obsolete or duplicate, PM owns moving that Story to Cancelled. Never cancel work merely because of
                        staffing, queue depth, or dependency delay. Otherwise repair a dependency/assignment; route product
                        ambiguity to PO; route integration/repository issues to ARCH; route implementation/testability
                        evidence to DEV/QA. Never mark defect Done after merge; QA retest closes defects. Never mutate
                        test lifecycle.
                        Never move Todo, In Progress, Done, or Cancelled work backward because of staffing, queue depth,
                        capacity, or dependency bookkeeping. Preserve flow over serialization: continue feeding independent
                        executable work while other units wait
                        on integration, deployment, or another role.
                        """))
                .to("tack:act?id=pm-flow&toolProfile=" + DeliveryManagementToolProfile.ID);

        from("direct:pm-focus-planning")
                .routeId("pm-focus-planning")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Focused planning across In Progress and ready Todo Epics: PO Gherkin, reasonable Story coverage,
                        dependencies, repository ownership, future Backlog work, and executable DEV/ARCH queues.
                        """))
                .to("tack:thought-enter?name=pm-planning-focus&maxTokens=550")
                .to("direct:pm-planning-loop")
                .to("tack:thought-exit?name=pm-planning-focus")
                .to("direct:pm-awareness");

        from("direct:pm-planning-loop")
                .routeId("pm-planning-loop")
                .setProperty("pmPlanningIterations", constant(0))
                .setProperty("pmPlanningContinue", constant(true))
                .loopDoWhile(simple("${exchangeProperty.pmPlanningContinue} == true && ${exchangeProperty.pmPlanningIterations} < 5"))
                    .setProperty("pmPlanningIterations", simple("${exchangeProperty.pmPlanningIterations++}"))
                    .setProperty("pmPlanningContinue", constant(false))
                    .to("direct:pm-planning")
                    .choice()
                        .when(exchangeProperty("act.pm-planning.durableProgress").isEqualTo(true))
                            .setProperty("pmPlanningContinue", constant(true))
                    .end()
                .end();

        from("direct:pm-planning")
                .routeId("pm-planning")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Plan across all PO-owned epic items in In Progress or Todo after Architect has established
                        the system repository plus component repository map. Read each selected Epic's PO-authored
                        Given/When/Then acceptance as the authoritative behavioral boundary. Reuse architecture as the
                        source of truth for repository/component boundaries; do not invent a new topology.

                        For an In Progress Epic, create/refine its reasonable non-duplicative implementation Story set and
                        an acyclic dependency graph, then replenish each DEV/ARCH runway to roughly 2-3 assigned executable
                        Todo Stories when legitimate dependency-satisfied work exists. Before adding a relation, verify it
                        does not create a dependency cycle; repair any verified existing cycle instead of preserving it. Inspect enough candidates to restore the
                        buffer in this pass rather than stopping after one promotion.

                        For a Todo Epic, pre-decompose the same reasonable Story/dependency set but leave its implementation
                        children Backlog. Todo at the Epic level means product/delivery ready for future activation, not
                        active engineering consumption. Do not promote child Stories to Todo until their parent Epic is
                        In Progress.

                        Ensure the story label exists and attach it explicitly. Normal component
                        work goes to DEV; system repository, integration, GitOps, cross-component, or architecture-heavy
                        work goes to ARCH. Before mutating an existing item, verify its classification from attached labels.
                        Todo Stories must have the intended engineer assigned, and they may remain staged even while that
                        engineer's AgentRun is temporarily absent. Never move existing Todo/In Progress/Done/Cancelled work
                        backward because an AgentRun is absent. Keep genuinely dependency-blocked future Stories Backlog.
                        Work owned by ARCH does not consume DEV capacity and vice versa. Do not create epic product
                        outcomes, test QA work, or defect findings.
                        """))
                .to("tack:act?id=pm-planning&toolProfile=" + DeliveryManagementToolProfile.ID);

        from("direct:pm-focus-coordination")
                .routeId("pm-focus-coordination")
                .to("direct:pm-awareness");

        from("direct:pm-awareness")
                .routeId("pm-awareness")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Use the current-mission shared channel. Send/reply once only when changed assignment, dependency,
                        defect routing, merge reconciliation, QA handoff, blocker, or WIP pressure will help another
                        routine act sooner. Chat is awareness only: acknowledgments, approval phrases, "green" signals,
                        and handoff messages never gate Plane lifecycle or Gitea integration. Missing channel access is
                        onboarding work, not a reason to use a DM. Avoid generic status.
                        """))
                .to("tack:act?id=pm-awareness&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:pm-focus-complete")
                .routeId("pm-focus-complete")
                .setProperty("decision.project-manager-mission-complete.result", constant("yes"));

        from("direct:pm-focus-wait")
                .routeId("pm-focus-wait")
                .delay(120000);
    }
}
