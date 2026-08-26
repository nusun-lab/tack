package us.nusun.llmshare.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import us.nusun.llmshare.example.tool.SourceControlToolProfile;
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
                        Full Director routine: understand current mission progress, maintain useful team coverage,
                        encourage collaboration, merge delivery-approved pull requests, and keep the mission moving
                        until durable evidence shows the entire mission is complete.
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
                        verified current-mission project basics, blockers, major handoffs, and last integration decision.
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
                        Current orchestration cycle: authoritative active roster coverage, project-basics readiness,
                        delivery handoffs, one integration-ready pull request when present, blockers, and useful staffing gaps.
                        """))
                    .to("tack:thought-enter?name=orchestration-cycle&maxTokens=300")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Read a small current-mission slice sufficient to understand project-basics readiness,
                                    overall progress, major blockers, and meaningful handoffs. Stay anchored to the current
                                    mission. Plane state is authoritative for work state; comments and chat provide handoff
                                    context but never substitute for Backlog, Todo, In Progress, Done, or Cancelled.
                                    Capture at most one In Progress implementation item whose PM review has produced a current
                                    INTEGRATION READY handoff and its linked pull request. Do not mutate anything or perform
                                    product ownership, delivery decomposition, implementation review, or source changes.
                                    If no current-mission workspace anchor exists yet, record pre-bootstrap state and stop.
                                    """))
                    .to("tack:act?id=director-workspace-review&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Own the final pull-request merge action for the team.
                                    Using only the current-mission evidence already gathered this cycle, inspect at most one
                                    pull request linked from an In Progress implementation task carrying a current
                                    INTEGRATION READY handoff from the Project Manager. Merge it only when the pull request is
                                    still open, targets the expected repository and base branch, the reviewed head commit has
                                    not changed, it is mergeable, and available validation evidence does not contradict the
                                    accepted review. Do not merge work that is merely REVIEW READY, blocked, ambiguous, or
                                    materially changed since PM review. Do not substitute your own implementation or product
                                    judgment for the PM's completed delivery review. If no eligible pull request exists, make
                                    no source-control change and stop.
                                    """))
                    .to("tack:act?id=director-integration-merge&toolProfile=" + SourceControlToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Read recent current-mission project chat. Participate only when useful to coordinate,
                                    encourage, clarify, or unblock real team work. Do not prescribe another role's internal
                                    workflow, duplicate durable status, or use chat activity as evidence that a teammate is
                                    actively running. If no verified current-mission channel is known, skip chat and stop.
                                    """))
                    .to("tack:act?id=director-team-guidance&toolProfile=" + TeamChatToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, simple("""
                        Decide which AgentRuns are useful now.

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

                        COVERAGE SOURCE OF TRUTH:
                        - The mission roster above is the only source of truth for active AgentRun coverage.
                        - Only accepted, scheduled, or running AgentRuns provide active coverage.
                        - Completed, failed, cancelled, or absent AgentRuns provide zero active coverage.
                        - Plane assignments, commits, pull requests, Mattermost messages, documentation, and other
                          project evidence never establish active AgentRun coverage.

                        USEFUL COVERAGE:
                        - The steady-state goal is active full-routine coverage from Product Owner, Project Manager,
                          and Application Developer while their capabilities remain useful to unfinished mission outcomes.
                        - Do not confuse eventual full-team coverage with starting every role immediately.
                        - A run is useful only when its routine prerequisites exist or the run itself owns creating them.
                        - The Project Manager main routine owns the shared delivery foundation: Plane workspace,
                          agent-workspace repositories, documentation/navigation, team chat, membership, and ongoing
                          delivery coordination.
                        - Product Owner and Application Developer routines explicitly require those project basics before
                          they can do their role work. When those basics are absent or not yet verified, starting those
                          roles only creates blocked coverage and does not advance the mission.
                        - Once project basics are durably established, keeping PM, PO, and DEV main-routine coverage
                          active is normally the lowest-touch way to let the team work continuously.
                        - If a previously useful run fails or completes while its role still has unfinished mission work,
                          replace that coverage.

                        STARTING RUNS:
                        - Choose the next missing capability from the dependency state above rather than from a fixed role order.
                        - Prefer full main routines. Use subroutines only for an explicit narrow need.
                        - Avoid duplicate active coverage.
                        - Start no more runs than remaining capacity.
                        - Give each new run responsibility for the whole mission through that role, with only concise
                          current-mission context needed to resume.
                        - Return {"agentRuns":[]} when current useful coverage is sufficient for now.

                        This should naturally produce one foundation-owning run during an unbootstrapped mission, then
                        expand to full team coverage after the shared workspace is usable, without hard-coded sequencing.
                        """))
                    .to("tack:create?id=planned-agent-runs&schemaId=agent-run-request-list&schema=classpath:schemas/example/software-team/agent-run-requests-schema.json")
                    .unmarshal().json(JsonLibrary.Jackson, MissionRequest.class)
                    .setBody(simple("${body.agentRuns}"))
                    .to("direct:start-agent-runs")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Review current mission completion status once using the smallest authoritative read
                                    available. Check current-mission Plane state, current-work/documentation status, and
                                    direct completion/blocker evidence only. One verified unfinished required outcome is
                                    enough to conclude not complete. Do not mutate anything or perform another role's work.
                                    """))
                    .to("tack:act?id=director-mission-status-review&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is the top-level mission 100% complete?
                                    Return true only when durable current-mission external evidence shows every required
                                    outcome is fully satisfied. Active coverage does not itself prove completion. Return
                                    false if any required outcome is incomplete, unclear, blocked, merely planned, waiting
                                    for active teammates, or lacking verified evidence.
                                    """))
                    .to("tack:decide?id=director-mission-complete")
                    .to("tack:thought-exit?name=orchestration-cycle")
                    .filter(exchangeProperty("decision.director-mission-complete.result").isNotEqualTo(true))
                        .delay(120000)
                    .end()
                .end()
                .end()
                .to("tack:thought-exit?name=orchestration-root");
    }
}
