package us.nusun.tack.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import us.nusun.tack.example.tool.TeamChatToolProfile;
import us.nusun.tack.example.tool.WorkspaceReadonlyToolProfile;
import us.nusun.tack.framework.routine.RoutineProvider;
import us.nusun.tack.framework.inference.LlmPassType;
import us.nusun.tack.framework.run.AgentRunRequest;
import us.nusun.tack.framework.routine.RoutineDefinition;
import us.nusun.tack.framework.run.AgentRunAdmissionService;
import us.nusun.tack.framework.routine.TackHeaders;

import java.util.List;

@ApplicationScoped
public class DirectorRoutine extends RouteBuilder implements RoutineProvider {
    public static final String ID = "software-team/director";
    static final int MAX_ACTIVE_MISSION_RUNS = 7;

    public record AgentRunBatch(List<AgentRunRequest> agentRuns) {}

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
                        Mission anchor, active roster and capacity. Preserve verified readiness and exact repository locators
                        (system/component owner/repo pairs), Kaneo workspace ID and project ID/slug, qualifying Epic and PO IDs, and
                        Mattermost team/channel IDs across synthesis and consolidation. Keep outstanding staffing gates and escalations.
                        """))
                .to("tack:thought-enter?name=director-root&maxTokens=450")
                .setProperty(AgentRunAdmissionService.CAPACITY_MAXIMUM_PROPERTY, constant(MAX_ACTIVE_MISSION_RUNS))
                .setProperty("decision.director-mission-complete.result", constant("no"))
                .loopDoWhile(exchangeProperty("decision.director-mission-complete.result").isNotEqualTo("yes"))
                    .to("direct:director-orientation")
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Choose exactly one highest-value Director focus.
                            - staffing: PM or an already-required role is missing, or fresh evidence warrants checking
                              a missing role's gate. Check project and channel readiness for PO and ARCH. Check the
                              documented repository architecture and a PO-owned to-do or in-progress Epic for DEV and QA.
                            - guidance: a fresh cross-role blocker, mission interpretation, or PO product-viability
                              question needs Director judgment.
                            - complete: all required Epic outcomes are accepted and done and no unresolved
                              delivery, architecture, QA, product, or mission blocker remains.
                            - wait: the mission is not complete and neither staffing nor Director judgment is useful now.
                            """))
                    .to("tack:decide?id=director-focus&choices=staffing,guidance,complete,wait")
                    .toD("direct:director-focus-${exchangeProperty.decision.director-focus.result}")
                .end()
                .end()
                .to("tack:thought-exit?name=director-root");

        from("direct:director-orientation")
                .routeId("director-orientation")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Check active roster coverage, changed staffing prerequisites, product completion and genuine Director escalations.
                        Reuse verified project, channel and repository readiness. Record a missing gate as an expected phase condition
                        unless evidence establishes an actual failure.
                        """))
                .to("tack:act?id=director-orientation&toolProfile=" + WorkspaceReadonlyToolProfile.ID);

        from("direct:director-focus-staffing")
                .routeId("director-focus-staffing")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Active roster and capacity, exact project/channel locators, repository architecture (verified system and
                        component owner/repo pairs), and qualifying Epic and PO user IDs. Preserve each verified gate and unknown.
                        """))
                .to("tack:thought-enter?name=director-staffing-focus&maxTokens=400")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read active roster coverage and capacity. PM is always required. Verify project and channel readiness
                        for PO and ARCH. For DEV and QA, verify both the system/component repository map and a PO-owned to-do
                        or in-progress task with the attached epic label. Match the PO's roster email to the member ID and
                        use assignee and status filters. A documented single component is sufficient. Preserve verified gates
                        and record unresolved gates as unknown. Product definition and repository setup precede DEV/QA staffing.
                        """))
                .to("tack:act?id=director-staffing-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, simple("""
                        Decide which missing AgentRuns are required now. Mission: ${exchangeProperty.mission.goal}
                        Assignable personas: ${bean:personaCatalog.agentRunStartAssignablePromptJson}
                        Available routines: ${bean:routineCatalog.startableJson}
                        Use the authoritative roster and remaining capacity from this read and the verified readiness evidence
                        in the active thoughts. An unknown gate does not establish eligibility.

                        Phase policy for an unfinished mission:
                        - Project Manager is always required.
                        - Once Kaneo project and team channel are usable, Product Owner and Architect are required.
                        - Once a PO-owned Epic with the epic label is to-do or in-progress AND Architect has established
                          the system repository plus the required component repository set, Developer and Quality Engineer
                          are required. Staffing can proceed while the Epic is still to-do.
                        - Keep already-required roles active through mission completion.
                        - Never duplicate an already-active role.
                        - Start one run for every missing required role that capacity allows. When a missing role has verified gates
                        and capacity remains, agentRuns must include it. If PM is missing, include it first.
                        Return {"agentRuns":[]} when no missing role is eligible or capacity prevents another start.
                        """))
                .to("tack:create?id=planned-agent-runs&schemaId=agent-run-request-list&schema=classpath:schemas/example/software-team/agent-run-requests-schema.json")
                .unmarshal().json(JsonLibrary.Jackson, AgentRunBatch.class)
                .setBody(simple("${body.agentRuns}"))
                .to("direct:start-agent-runs")
                .to("tack:thought-exit?name=director-staffing-focus");

        from("direct:director-focus-guidance")
                .routeId("director-focus-guidance")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Exact channel/thread and relevant work locators for one mission interpretation, viability or cross-role issue.
                        Keep the decision and next actionable condition.
                        """))
                .to("tack:thought-enter?name=director-guidance-focus&maxTokens=350")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read only recent current-mission team conversation needed for one Director-level intervention.
                        Respond when PO asks about product viability or mission interpretation, or when a cross-role blocker
                        needs a decision above PO/PM/ARCH/DEV/QA ownership. Do not invent source-control approval roles,
                        Story assignments, or delivery sequencing when existing role policy already owns them. Keep Product
                        Owner authority over epics and acceptance. Avoid generic status and avoid doing another role's work.
                        """))
                .to("tack:act?id=director-guidance&toolProfile=" + TeamChatToolProfile.ID)
                .to("tack:thought-exit?name=director-guidance-focus");

        from("direct:director-focus-complete")
                .routeId("director-focus-complete")
                .setProperty("decision.director-mission-complete.result", constant("yes"));

        from("direct:director-focus-wait")
                .routeId("director-focus-wait")
                .delay(120000);
    }
}
