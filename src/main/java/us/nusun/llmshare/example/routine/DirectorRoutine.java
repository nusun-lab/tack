package us.nusun.llmshare.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import us.nusun.llmshare.example.tool.TeamChatToolProfile;
import us.nusun.llmshare.example.tool.WorkspaceReadonlyToolProfile;
import us.nusun.llmshare.framework.inventory.RoutineProvider;
import us.nusun.llmshare.framework.model.LlmPassType;
import us.nusun.llmshare.framework.model.MissionRequest;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.service.AgentRunAdmissionService;
import us.nusun.llmshare.framework.tack.TackHeaders;

import java.util.List;

@ApplicationScoped
public class DirectorRoutine extends RouteBuilder implements RoutineProvider {
    public static final String ID = "software-team/director";
    static final int MAX_ACTIVE_MISSION_RUNS = 7;

    @Override
    public List<RoutineDefinition> routines() {
        return List.of(routine());
    }

    public RoutineDefinition routine() {
        return RoutineDefinition.builder(ID)
                .name("Director")
                .main("Direct mission execution and product viability through completion.", "direct:example-software-team-director")
                .defaultLane("high-pass")
                .withLaneBinding(LlmPassType.SYNTHESIS, "low-pass")
                .withLaneBinding(LlmPassType.CONSOLIDATION, "low-pass")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-software-team-director")
                .routeId("example-software-team-director")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Durable mission anchors: mission goal, authoritative active roster, shared coordination readiness,
                        active typed Epics, major blockers, product-viability questions, and last focused outcome.
                        """))
                .to("tack:thought-enter?name=director-root&maxTokens=450")
                .setProperty(AgentRunAdmissionService.CAPACITY_MAXIMUM_PROPERTY, constant(MAX_ACTIVE_MISSION_RUNS))
                .setProperty("decision.director-mission-complete.result", constant("no"))
                .loopDoWhile(exchangeProperty("decision.director-mission-complete.result").isNotEqualTo("yes"))
                    .to("direct:director-orientation")
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Choose exactly one highest-value Director focus.
                            - staffing: a role required by the current mission phase is missing from the authoritative
                              active AgentRun roster.
                            - guidance: a fresh cross-role blocker, mission interpretation, or PO product-viability
                              question needs Director judgment.
                            - wait: neither staffing nor Director judgment is useful now.
                            Director does not integrate pull requests; Architect owns integration. Director does not own
                            backlog decomposition; PO owns typed Epics and PM owns delivery flow. Choose exactly one value.
                            """))
                    .to("tack:decide?id=director-focus&choices=staffing,guidance,wait")
                    .toD("direct:director-focus-${exchangeProperty.decision.director-focus.result}")
                .end()
                .end()
                .to("tack:thought-exit?name=director-root");

        from("direct:director-orientation")
                .routeId("director-orientation")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read the smallest fresh current-mission slice needed to identify: authoritative active AgentRun
                        coverage/capacity; whether one Plane project and team channel are usable; whether Architect has
                        established the current mission system repository plus the component repository set required by
                        its architecture; whether a PO-owned Epic work item is active In Progress; one fresh
                        mission/product-viability question; and one major cross-role blocker. External artifacts never
                        prove active AgentRun coverage. Do not mutate.
                        """))
                .to("tack:act?id=director-orientation&toolProfile=" + WorkspaceReadonlyToolProfile.ID);

        from("direct:director-focus-staffing")
                .routeId("director-focus-staffing")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Verify staffing phase signals only: authoritative active AgentRun roster/capacity; coordination
                        foundation readiness (Plane project plus team channel); repository-architecture readiness
                        (exactly one mission system repository plus the component repository set documented by Architect);
                        and whether at least one PO-owned Epic is In Progress. Do not require repositories before Architect
                        is staffed. Do not infer active roles from assignments, commits, chat, or repository identities.
                        """))
                .to("tack:act?id=director-staffing-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, simple("""
                        Decide which missing AgentRuns are required now. Mission: ${exchangeProperty.mission.goal}
                        Assignable personas: ${bean:personaCatalog.agentRunStartAssignablePromptJson}
                        Available routines: ${bean:routineCatalog.startableJson}
                        Use only authoritative active roster evidence captured in this focus.

                        Phase policy for an unfinished mission:
                        - Project Manager is always required.
                        - Once Plane project and team channel are usable, Product Owner and Architect are required.
                        - Once at least one PO-owned Epic is In Progress AND Architect has established the system
                          repository plus the required component repository set, Developer and Quality Engineer are required.
                        - Keep already-required roles active through mission completion.
                        - Never duplicate an already-active role.
                        - Start one run per missing required role subject to remaining mission capacity.
                        Return {"agentRuns":[]} when none is missing or capacity prevents another start.
                        """))
                .to("tack:create?id=planned-agent-runs&schemaId=agent-run-request-list&schema=classpath:schemas/example/software-team/agent-run-requests-schema.json")
                .unmarshal().json(JsonLibrary.Jackson, MissionRequest.class)
                .setBody(simple("${body.agentRuns}"))
                .to("direct:start-agent-runs");

        from("direct:director-focus-guidance")
                .routeId("director-focus-guidance")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read only recent current-mission team conversation needed for one Director-level intervention.
                        Respond when PO asks about product viability or mission interpretation, or when a cross-role blocker
                        needs a decision above PO/PM/ARCH/DEV/QA ownership. Keep Product Owner authority over epics and
                        acceptance. Avoid generic status and avoid doing another role's work.
                        """))
                .to("tack:act?id=director-guidance&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:director-focus-wait")
                .routeId("director-focus-wait")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read only enough current evidence to decide mission completion. Answer yes only when all required
                        typed product Epics are accepted Done and no unresolved delivery, architecture, QA, product, or mission
                        blocker remains. Otherwise answer no.
                        """))
                .to("tack:act?id=director-completion-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Is the mission complete according to the current evidence? Answer yes only for fully satisfied
                        mission outcomes. Answer no for anything incomplete, unclear, blocked, or lacking required evidence.
                        """))
                .to("tack:decide?id=director-mission-complete")
                .choice()
                    .when(exchangeProperty("decision.director-mission-complete.result").isNotEqualTo("yes"))
                        .delay(120000)
                .end();
    }
}
