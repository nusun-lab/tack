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
                        use the available personas and routines, start only useful runs, encourage real collaboration,
                        give active teammates time to move the work, and temporarily merge delivery-approved pull requests
                        until a dedicated architecture role owns integration.
                        """, "direct:example-software-team-director")
                .defaultLane("high-pass")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-software-team-director")
                .routeId("example-software-team-director")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Durable orchestration anchors: mission goal, roster by status, active/completed/failed coverage,
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
                                            Check direct anchors and one relevant work item before deciding. Capture any reviewed implementation
                                            work whose pull request is ready for integration. Do not mutate or sweep systems.
                                            """))
                            .to("tack:act?id=director-workspace-review&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                            .setHeader(TackHeaders.PROMPT, constant("""
                                            Temporarily own the final merge action until a dedicated architecture role exists.
                                            Using the durable project evidence already gathered this cycle, inspect at most one pull request
                                            associated with implementation work whose delivery review has already been accepted. Merge it only
                                            when the pull request is still open, targets the expected repository/branch, is mergeable, and available
                                            validation/review evidence does not contradict the accepted delivery state. Do not merge work that is
                                            still in progress, review, blocked, unreviewed, or ambiguous. Do not substitute your own product or
                                            implementation judgment for the completed delivery review.
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
                                
                                Note: Completed runs are not "idle" agents that will start again on their own. They have completed
                                their routine with the instructions provided. If you want them to continue, you must
                                start them with new instructions.
                                
                                Goals:
                                Your end-goal is launch the full team running their main routine with high-level directives
                                so that you can be as low-touch as possible. That may not be appropriate when the project
                                is in early stages, but you should work toward that outcome with each pass.
                                
                                Instructions:
                                Use the current runtime state, mission evidence, persona catalog, and routine catalog to cover the next missing capability.
                                Prefer full main routines, avoid duplicate active coverage, and use subroutines only for explicit narrow needs.
                                Start no more runs than remaining capacity; return {"agentRuns":[]} when active coverage is enough for now.
                                For each run, name the missing capability, durable evidence, and why this persona/routine is the right next consumer.
                                
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
