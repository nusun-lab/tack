package us.nusun.llmshare.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.framework.inventory.RoutineProvider;
import org.apache.camel.model.dataformat.JsonLibrary;
import us.nusun.llmshare.framework.model.MissionRequest;
import us.nusun.llmshare.framework.service.AgentRunAdmissionService;
import us.nusun.llmshare.framework.service.AgentRunCapacityService;
import us.nusun.llmshare.framework.service.MissionRosterService;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.tack.TackHeaders;

import java.util.List;

import static org.apache.camel.builder.Builder.simple;
import us.nusun.llmshare.example.tool.SourceControlToolProfile;
import us.nusun.llmshare.example.tool.TeamChatToolProfile;
import us.nusun.llmshare.example.tool.WorkspaceReadonlyToolProfile;

@ApplicationScoped
public class DirectorRoutine extends RouteBuilder implements RoutineProvider {
    public static final String ID = "software-team/director";
    static final int MAX_ACTIVE_MISSION_RUNS = 5;

    @Inject AgentRunCapacityService agentRunCapacityService;
    @Inject MissionRosterService missionRosterService;

    @Override
    public List<RoutineDefinition> routines() {
        return List.of(routine());
    }

    public RoutineDefinition routine() {
        return RoutineDefinition.builder(ID)
                .name("Director")
                .main("""
                        Full Director routine: self-directed mission orchestration. Watch capacity and mission evidence,
                        use the available personas and routines, maintain useful forward capability coverage, encourage real
                        collaboration, delegate substantial outcomes with room for role judgment, and temporarily merge
                        delivery-approved pull requests until a dedicated architecture role owns integration.
                        """, "direct:example-software-team-director")
                .defaultLane("high-pass")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-software-team-director")
                .routeId("example-software-team-director")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Durable orchestration anchors: mission goal, active capability coverage, completed/failed run history,
                        verified project state, last provisioning decision, and last integration decision.
                        """))
                .to("tack:thought-enter?name=orchestration-root&maxTokens=500")
                .setProperty("directorCycle", constant(0))
                .setProperty("decision.director-mission-complete.result", constant(false))
                .setProperty(AgentRunAdmissionService.CAPACITY_MAXIMUM_PROPERTY, constant(MAX_ACTIVE_MISSION_RUNS))
                .loopDoWhile(exchangeProperty("decision.director-mission-complete.result").isNotEqualTo(true))
                    .setProperty("directorCycle", simple("${exchangeProperty.directorCycle++}"))
                    .setProperty("directorCapacity", method(agentRunCapacityService, "capacityForCurrentMission"))
                    .setProperty("directorActiveRunCount", simple("${exchangeProperty.directorCapacity.active}"))
                    .setProperty("directorRemainingRunCapacity", simple("${exchangeProperty.directorCapacity.remaining}"))
                    .choice()
                        .when(exchangeProperty("directorActiveRunCount").isGreaterThanOrEqualTo(MAX_ACTIVE_MISSION_RUNS))
                            .delay(900000)
                            .endChoice()
                        .otherwise()
                            .setProperty("directorActiveMissionRoster", method(missionRosterService, "activeMissionRosterPrompt"))
                            .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                                Current orchestration cycle: active run coverage, workspace signals, completed/failed run implications,
                                integration-ready pull request evidence, staffing gap, planned AgentRunRequests or no-op decision.
                                Promote durable staffing and integration state to orchestration root before cycle exits.
                                """))
                            .to("tack:thought-enter?name=orchestration-cycle&maxTokens=300")
                            .setHeader(TackHeaders.PROMPT, constant("""
                                            Read the smallest mission-relevant slice needed to identify the next missing capability.
                                            Prefer durable project-management and documentation evidence; use chat as context, not source of truth.
                                            Check direct anchors and one relevant work item before deciding. Capture any Review item with
                                            an exact INTEGRATION READY handoff and its pull request. Do not mutate or sweep systems.
                                            """))
                            .to("tack:act?id=director-workspace-review&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                            .setHeader(TackHeaders.PROMPT, constant("""
                                            Temporarily own the final merge action until a dedicated architecture role exists.
                                            Using the durable project evidence already gathered this cycle, inspect at most one pull request
                                            associated with a Review item carrying an exact INTEGRATION READY handoff. Merge it only when the
                                            pull request is still open, targets the expected repository and branch, is mergeable, and available
                                            validation or review evidence does not contradict the accepted delivery state. Do not merge work
                                            that lacks INTEGRATION READY, is blocked, is ambiguous, or has changed materially since delivery review.
                                            Do not substitute your own product or implementation judgment for the completed delivery review.
                                            """))
                            .to("tack:act?id=director-integration-merge&toolProfile=" + SourceControlToolProfile.ID)
                            .setHeader(TackHeaders.PROMPT, constant("""
                                            Read recent project chat. Encourage useful teammate interaction when it helps people learn or unblock each other.
                                            Do not post generic status, duplicate durable state, or use reminders instead of staffing needed work.
                                            """))
                            .to("tack:act?id=director-team-guidance&toolProfile=" + TeamChatToolProfile.ID)
                            .setHeader(TackHeaders.PROMPT, simple("""
                                Decide whether the mission needs more agent runs.
                                
                                Mission:
                                ${exchangeProperty.mission.goal}

                                Assignable personas:
                                ${bean:personaCatalog.agentRunStartAssignablePromptJson}
                                
                                Available routines:
                                ${bean:routineCatalog.startableJson}
                                
                                Current Runtime State:
                                The following describes the active runtime state. It supersedes tool evidence as the source
                                of truth for the runtime. Work may be in an unfinished state.
                                
                                Capacity: ${exchangeProperty.directorActiveRunCount}/${exchangeProperty.agentRunCapacityMaximum} active, ${exchangeProperty.directorRemainingRunCapacity} remaining.
                                Current accepted/scheduled/running runs: ${exchangeProperty.directorActiveMissionRoster}
                                
                                Note: Only accepted, scheduled, or running AgentRuns provide active capability coverage.
                                Completed and failed runs are run history only and provide zero active capability coverage.
                                A completed run is not an idle, available, waiting, standing-by, or ready teammate. If you want
                                that persona to continue, start a new AgentRun.
                                
                                Goals:
                                Build useful active team coverage with high-level directives so roles can operate with minimal orchestration.
                                
                                Instructions:
                                Use the current runtime state, mission evidence, persona catalog, and routine catalog to maintain useful
                                capability coverage for continued mission progress.

                                Long, sustained AgentRuns on high-level responsibilities are generally more efficient than frequent short runs.
                                They preserve useful working context, reuse established workspace knowledge, and avoid repeated startup,
                                discovery, and orientation overhead. Prefer continuing meaningful responsibility within an AgentRun until a
                                natural handoff, blocker, or locally complete outcome is reached.

                                Delegate substantial responsibilities using the available routines. Avoid duplicate coverage and speculative work,
                                but do not wait for one role to finish before starting another clearly useful current or downstream capability.

                                Delegate responsibility and desired outcomes, not another role's internal decisions. Give each run enough
                                verified context to orient itself, then let the selected persona and routine inspect durable state and decide
                                how to perform its responsibility. Do not prescribe product decisions, delivery decomposition, assignment,
                                or implementation choices that belong to the selected role unless they are explicit mission constraints.

                                Start no more runs than remaining capacity; return {"agentRuns":[]} when useful active coverage is sufficient.
                                Keep run objectives broad and concise.
                                
                                """))
                            .to("tack:create?id=planned-agent-runs&schemaId=agent-run-request-list&schema=classpath:schemas/example/software-team/agent-run-requests-schema.json")
                            .unmarshal().json(JsonLibrary.Jackson, MissionRequest.class)
                            .setBody(simple("${body.agentRuns}"))
                            .to("direct:start-agent-runs")
                            .setHeader(TackHeaders.PROMPT, constant("""
                                            Is the top-level mission 100% complete?
                                            Return true only when durable external evidence shows the mission goal is
                                            fully satisfied.
                                            Return false if any required outcome is incomplete, unclear, blocked,
                                            merely planned, waiting for active teammates, or lacking verified evidence.
                                            """))
                            .to("tack:decide?id=director-mission-complete")
                            .to("tack:thought-exit?name=orchestration-cycle")
                            .filter(exchangeProperty("decision.director-mission-complete.result").isNotEqualTo(true))
                                .delay(120000)
                            .end()
                        .end()
                    .end()
                .end()
                .to("tack:thought-exit?name=orchestration-root");
    }

}
