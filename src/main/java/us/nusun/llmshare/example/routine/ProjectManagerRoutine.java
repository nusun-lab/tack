package us.nusun.llmshare.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.example.tool.DeliveryManagementToolProfile;
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
                .main("Manage delivery flow, synchronization, and WIP across the team.",
                        "direct:example-software-team-project-manager")
                .subroutine("bootstrap", "Establish shared coordination basics.", "direct:pm-bootstrap")
                .subroutine("planning", "Turn active Epics into executable Story work.", "direct:pm-planning")
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
                        Durable flow anchors: Plane project, team channel, active roster, active Epic, Story queue,
                        QA queue, defects, dependencies, PR consequences, blockers, WIP, and cross-role handoffs.
                        """))
                .to("tack:thought-enter?name=pm-root&maxTokens=450")
                .setProperty("decision.project-manager-mission-complete.result", constant("no"))
                .loopDoWhile(exchangeProperty("decision.project-manager-mission-complete.result").isNotEqualTo("yes"))
                    .to("direct:pm-orientation")
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Choose exactly one highest-value Project Manager focus.
                            - bootstrap: shared Plane project or team-channel basics are missing.
                            - onboarding: a current-roster teammate lacks project/channel access or verified access drift exists.
                            - defect-flow: a PO-triaged open work:defect Bug needs repair assignment/promotion, or a repaired
                              Bug needs QA awareness for retest.
                            - active-flow: dependency promotion/repair, stale assignment, engineer-owned Story completion
                              awareness, blocker routing, QA handoff, or another active-Epic synchronization issue is actionable.
                            - planning: repository architecture is established and the active Epic lacks enough executable
                              work:implementation Story coverage or safe forward work for DEV/ARCH. If the system/component
                              repository map is not established yet, route the architecture dependency instead of inventing
                              implementation topology.
                            - coordination: a fresh delivery question needs PM participation.
                            - wait: none of the above is useful now.
                            Prefer finishing active Epic WIP over expanding queues. Choose exactly one value.
                            """))
                    .to("tack:decide?id=pm-focus&choices=bootstrap,onboarding,defect-flow,active-flow,planning,coordination,wait")
                    .toD("direct:pm-focus-${exchangeProperty.decision.pm-focus.result}")
                .end()
                .end()
                .to("tack:thought-exit?name=pm-root");

        from("direct:pm-orientation")
                .routeId("pm-orientation")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read the smallest current-mission slice needed to rank PM focus. Capture: authoritative active
                        roster; Plane project/channel readiness; whether Architect has established the mission system
                        repository and component repository map; the typed Epic currently In Progress; its Story children;
                        work:test QA children; work:defect Bugs; assignments/lifecycle/dependencies; current linked PR
                        state only when needed; one blocker/handoff; and one fresh delivery exchange. A defect is ready
                        for PM repair routing when PO has supplied valid Epic parentage and product priority. QA owns
                        work:test lifecycle. PO owns Epic lifecycle. Report observations only. Do not mutate.
                        """))
                .to("tack:act?id=pm-orientation&toolProfile=" + WorkspaceReadonlyToolProfile.ID);

        from("direct:pm-focus-bootstrap")
                .routeId("pm-focus-bootstrap")
                .to("direct:pm-bootstrap");

        from("direct:pm-bootstrap")
                .routeId("pm-bootstrap")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Establish only the shared coordination foundation: one mission Plane project and one team channel,
                        with current-roster access as identities become available. Do not create source/docs repositories.
                        Architect owns system and component repository provisioning.
                        """))
                .to("tack:act?id=pm-bootstrap&toolProfile=" + WorkspaceCoordinationToolProfile.ID)
                .to("direct:pm-awareness");

        from("direct:pm-focus-onboarding")
                .routeId("pm-focus-onboarding")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Compare the authoritative current mission roster with actual Plane project and team-channel
                        membership. Add only resolved current-roster identities that are missing. Do not infer access from
                        roster presence and do not manage repository architecture.
                        """))
                .to("tack:act?id=pm-onboarding&toolProfile=" + WorkspaceCoordinationToolProfile.ID)
                .to("direct:pm-awareness");

        from("direct:pm-focus-defect-flow")
                .routeId("pm-focus-defect-flow")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Advance one work:defect Bug without changing product judgment. For a PO-triaged Backlog Bug with
                        valid Epic parent and non-none product priority, choose the appropriate active engineer from
                        evidence: ordinary component behavior -> DEV; system integration/repository/GitOps/cross-cutting
                        architecture -> ARCH. Assign it and move it to Todo. Do not create a repair Story.
                        For an In Progress Bug whose fix PR is now merged, keep the Bug In Progress and surface concise
                        evidence to QA for retest; QA owns the final Done transition. Do not route an untriaged defect.
                        """))
                .to("tack:act?id=pm-defect-flow&toolProfile=" + DeliveryManagementToolProfile.ID)
                .to("direct:pm-awareness");

        from("direct:pm-focus-active-flow")
                .routeId("pm-focus-active-flow")
                .to("direct:pm-flow");

        from("direct:pm-flow")
                .routeId("pm-flow")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Advance one synchronization issue under the active Epic. When an assigned Story PR is merged but
                        the Story is still In Progress, notify the assigned engineer to complete its lifecycle rather than
                        closing it for them. Promote one newly unblocked Backlog Story to assigned Todo when engineer queue
                        capacity permits; repair a dependency/assignment; route product ambiguity to PO; route
                        integration/repository issues to ARCH; route implementation/testability evidence to DEV/QA.
                        Never mark work:defect Done after merge; QA retest closes defects. Never mutate work:test lifecycle.
                        Prefer closing current WIP before starting more.
                        """))
                .to("tack:act?id=pm-flow&toolProfile=" + DeliveryManagementToolProfile.ID)
                .to("direct:pm-awareness");

        from("direct:pm-focus-planning")
                .routeId("pm-focus-planning")
                .to("direct:pm-planning");

        from("direct:pm-planning")
                .routeId("pm-planning")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Plan only beneath the one typed Epic In Progress and only after Architect has established the
                        system repository plus component repository map. Reuse that architecture as the source of truth
                        for repository/component boundaries; do not invent a new topology. Resolve the Story work-item
                        type and ensure the work:implementation label exists. Create/refine the smallest coherent
                        Story/dependency set needed to deliver the Epic, using parent=<Epic UUID> and the exact Story type
                        id. Normal component work
                        goes to DEV; system repository, integration, GitOps, cross-component, or architecture-heavy work
                        goes to ARCH. Assign Todo only to an engineer with an authoritative active AgentRun; when the
                        required role is not active yet, keep otherwise-ready work Backlog for later handoff. Keep
                        dependency-blocked future Stories Backlog. Maintain low source WIP: normally one In Progress source
                        unit per DEV/ARCH and at most one assigned Todo source unit queued ahead for each. Do not create
                        Epics, QA work:test Tasks, or defect Bugs.
                        """))
                .to("tack:act?id=pm-planning&toolProfile=" + DeliveryManagementToolProfile.ID)
                .to("direct:pm-awareness");

        from("direct:pm-focus-coordination")
                .routeId("pm-focus-coordination")
                .to("direct:pm-awareness");

        from("direct:pm-awareness")
                .routeId("pm-awareness")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Send/reply once only when changed assignment, dependency, defect routing, merge reconciliation,
                        QA handoff, blocker, or WIP pressure will help another routine act sooner. Avoid generic status.
                        """))
                .to("tack:act?id=pm-awareness&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:pm-focus-wait")
                .routeId("pm-focus-wait")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Is PM work complete? Answer yes only when every required Epic is terminal, no Story/defect/test
                        delivery remains active, and no synchronization/reconciliation blocker remains.
                        """))
                .to("tack:decide?id=project-manager-mission-complete")
                .choice()
                    .when(exchangeProperty("decision.project-manager-mission-complete.result").isNotEqualTo("yes"))
                        .delay(120000)
                .end();
    }
}
