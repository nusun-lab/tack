package us.nusun.llmshare.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import us.nusun.llmshare.example.tool.TeamChatToolProfile;
import us.nusun.llmshare.example.tool.WorkspaceReadonlyToolProfile;
import us.nusun.llmshare.framework.inventory.RoutineProvider;
import us.nusun.llmshare.framework.model.MissionRequest;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.service.AgentRunAdmissionService;
import us.nusun.llmshare.framework.service.AgentRunCapacityService;
import us.nusun.llmshare.framework.service.MissionRosterService;
import us.nusun.llmshare.framework.tack.TackHeaders;

import java.util.List;

import static org.apache.camel.builder.Builder.simple;

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
                        Full Director routine: understand current mission progress, interact with the team when useful,
                        and keep full active coverage with the Product Owner, Project Manager, and Application Developer
                        main routines until durable evidence shows the entire mission is complete.
                        """, "direct:example-software-team-director")
                .defaultLane("high-pass")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-software-team-director")
                .routeId("example-software-team-director")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Durable orchestration anchors: mission goal, authoritative active roster coverage,
                        completed/failed run history, verified current-mission project state, blockers, and major handoffs.
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
                    .setProperty("directorActiveMissionRoster", method(missionRosterService, "activeMissionRosterPrompt"))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Current orchestration cycle: authoritative active roster coverage, a small current-mission project read,
                        meaningful team interaction, missing PM/PO/DEV full-routine coverage, and planned replacement AgentRuns.
                        """))
                    .to("tack:thought-enter?name=orchestration-cycle&maxTokens=300")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Read a small current-mission slice sufficient to understand overall progress, major blockers,
                                    and meaningful handoffs. Stay anchored to the current mission. Do not perform product ownership,
                                    project-management decomposition, implementation review, integration, or source changes.
                                    External tool observations describe project state only. They never establish active team coverage.
                                    """))
                    .to("tack:act?id=director-workspace-review&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Read recent current-mission project chat. Participate only when useful to coordinate,
                                    encourage, clarify, or unblock real team work. Do not prescribe another role's internal workflow,
                                    duplicate durable status, or use chat activity as evidence that a teammate is actively running.
                                    """))
                    .to("tack:act?id=director-team-guidance&toolProfile=" + TeamChatToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, simple("""
                        Maintain full active team coverage for the entire mission.

                        Mission:
                        ${exchangeProperty.mission.goal}

                        Assignable personas:
                        ${bean:personaCatalog.agentRunStartAssignablePromptJson}

                        Available routines:
                        ${bean:routineCatalog.startableJson}

                        AUTHORITATIVE ACTIVE COVERAGE:
                        ${exchangeProperty.directorActiveMissionRoster}

                        Capacity: ${exchangeProperty.directorActiveRunCount}/${exchangeProperty.agentRunCapacityMaximum} active,
                        ${exchangeProperty.directorRemainingRunCapacity} remaining.

                        ACTIVE COVERAGE RULE:
                        - The mission roster above is the ONLY source of truth for active coverage.
                        - Only accepted, scheduled, or running AgentRuns provide active coverage.
                        - Completed, failed, cancelled, or absent AgentRuns provide zero active coverage.
                        - Plane assignments, work-item owners, commits, pull requests, repository activity, Mattermost messages,
                          documentation, handoffs, and any other tool observations NEVER establish active coverage.
                        - Never infer that a teammate is working from external project evidence.

                        REQUIRED COVERAGE:
                        - one active Product Owner running the software-team/product-owner main routine
                        - one active Project Manager running the software-team/project-manager main routine
                        - one active Application Developer running the software-team/application-developer main routine

                        Create an AgentRun for every required role that lacks active full-routine coverage, subject only to remaining capacity.
                        Do not create duplicate coverage for a role already represented by an accepted, scheduled, or running main-routine run.
                        Always use the full main routine, not a subroutine.

                        Give each new run responsibility for completing the WHOLE mission through that role until mission completion.
                        Keep objectives broad and concise. You may include one short piece of current-mission context that helps the role resume,
                        but do not prescribe product decisions, delivery decomposition, assignments, phases, slices, or implementation choices.

                        Return {"agentRuns":[]} only when PM, PO, and DEV all have authoritative active coverage or capacity prevents another start.
                        """))
                    .to("tack:create?id=planned-agent-runs&schemaId=agent-run-request-list&schema=classpath:schemas/example/software-team/agent-run-requests-schema.json")
                    .unmarshal().json(JsonLibrary.Jackson, MissionRequest.class)
                    .setBody(simple("${body.agentRuns}"))
                    .to("direct:start-agent-runs")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is the top-level mission 100% complete?
                                    Return true only when durable current-mission external evidence shows every required outcome is fully satisfied.
                                    Active coverage does not itself prove completion. Return false if any required outcome is incomplete, unclear,
                                    blocked, merely planned, waiting for active teammates, or lacking verified evidence.
                                    """))
                    .to("tack:decide?id=director-mission-complete")
                    .to("tack:thought-exit?name=orchestration-cycle")
                    .filter(exchangeProperty("decision.director-mission-complete.result").isNotEqualTo(true))
                        .delay(120000)
                    .end()
                .end()
                .to("tack:thought-exit?name=orchestration-root");
    }
}
