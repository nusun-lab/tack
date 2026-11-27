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
import us.nusun.tack.framework.tool.ToolProfileRegistry;

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
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read mission_roster with view coverage. Record active roles and available capacity.
                        PM is always required. A verified PM vacancy can be filled before any workspace exists.
                        """))
                .to("tack:act?id=director-startup-roster&toolProfile=" + ToolProfileRegistry.NONE_ID)
                .to("direct:director-focus-staffing")
                .setProperty("decision.director-mission-complete.result", constant("no"))
                .loopDoWhile(exchangeProperty("decision.director-mission-complete.result").isNotEqualTo("yes"))
                    .to("direct:director-evidence")
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Choose exactly one highest-value Director focus.
                            - staffing: the latest evidence verifies an eligible role is absent and capacity is available.
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

        from("direct:director-evidence")
                .routeId("director-evidence")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read mission_roster with view coverage for active roles and capacity. For missing PO or ARCH, check usability
                        of the current-mission project and channel. For missing DEV or QA, verify the documented system/
                        component repository baseline and one PO-owned to-do or in-progress task with the attached epic
                        label. Resolve the PO's exact member ID and use assignee/status filters. Reuse verified gates.
                        Report eligible vacancies and unknown gates. PM creates project/channel, PO readies the Epic,
                        and ARCH establishes repositories. With all roles active, check changed completion or escalation.
                        """))
                .to("tack:act?id=director-staffing-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID);

        from("direct:director-focus-staffing")
                .routeId("director-focus-staffing")
                .setHeader(TackHeaders.PROMPT, simple("""
                        Decide which missing AgentRuns are required now. Mission: ${exchangeProperty.mission.goal}
                        Assignable personas: ${bean:personaCatalog.agentRunStartAssignablePromptJson}
                        Available routines: ${bean:routineCatalog.startableJson}
                        Use the authoritative roster and remaining capacity from this read and the verified readiness evidence
                        in the active thoughts. An unknown gate does not establish eligibility.

                        Eligibility for an unfinished mission:
                        - Project Manager is always required.
                        - Once Kaneo project and team channel are usable, Product Owner and Architect are required.
                        - Developer and Quality Engineer are required once the documented system and component repository
                          baselines are verified and a PO-owned Epic with the epic label is to-do or in-progress.
                          Verified artifacts establish eligibility. Start all eligible roles in the same batch.
                        - Keep already-required roles active through mission completion.
                        - Never duplicate an already-active role.
                        - Start one run for every missing required role that capacity allows. When a missing role has verified gates
                        and capacity remains, agentRuns must include it. If PM is missing, include it first.
                        Start the whole-mission routine. It receives the shared mission and owns its next action.
                        Return {"agentRuns":[]} when no missing role is eligible or capacity prevents another start.
                        """))
                .to("tack:create?id=planned-agent-runs&schemaId=agent-run-request-list&schema=classpath:schemas/example/software-team/agent-run-requests-schema.json")
                .unmarshal().json(JsonLibrary.Jackson, AgentRunBatch.class)
                .setBody(simple("${body.agentRuns}"))
                .to("direct:start-agent-runs");

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
                .delay(60000);
    }
}
