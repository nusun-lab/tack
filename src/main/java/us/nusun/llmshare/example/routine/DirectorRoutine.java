package us.nusun.llmshare.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import us.nusun.llmshare.example.tool.IntegrationToolProfile;
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
                        delivery handoffs, integration-ready pull requests, blockers, and useful staffing gaps.
                        """))
                    .to("tack:thought-enter?name=orchestration-cycle&maxTokens=300")
                    .to("direct:director-integration-loop")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Read recent current-mission project chat. Participate only when useful to coordinate,
                                    encourage, clarify, or unblock real team work. Do not prescribe another role's internal
                                    workflow, duplicate durable status, or use chat activity as evidence that a teammate is
                                    actively running. If no verified current-mission channel is known, skip chat and stop.
                                    """))
                    .to("tack:act?id=director-team-guidance&toolProfile=" + TeamChatToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Freshly verify only the shared project basics needed for staffing this mission.
                                    Determine whether there is one usable current-mission Plane project, one source repository
                                    under agent-workspace, one documentation repository with usable navigation, one current-mission
                                    team-chat channel, and usable team access. Repository existence is sufficient: the source
                                    repository may legitimately have zero commits before Application Development starts.

                                    Product work items, implementation children, source commits, tests, pull requests, and
                                    application code are NOT project-basics prerequisites. Their absence must never block
                                    Product Owner or Application Developer staffing once the shared foundation itself exists.
                                    Do not mutate anything. Stop after the smallest authoritative reads needed to decide.
                                    """))
                    .to("tack:act?id=director-staffing-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is the shared delivery foundation currently usable for Product Owner and Application Developer work?
                                    Return true only when Plane project, source repository, documentation/navigation, team chat,
                                    and usable access are verified. An empty source repository is valid. Work-item or code absence
                                    does not make the foundation incomplete.
                                    """))
                    .to("tack:decide?id=director-foundation-ready")
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

                        FOUNDATION READY:
                        ${exchangeProperty.decision.director-foundation-ready.result}

                        Capacity: ${exchangeProperty.directorActiveRunCount}/${exchangeProperty.agentRunCapacityMaximum} active,
                        ${exchangeProperty.directorRemainingRunCapacity} remaining.

                        COVERAGE SOURCE OF TRUTH:
                        - The mission roster above is the only source of truth for active AgentRun coverage.
                        - Only accepted, scheduled, or running AgentRuns provide active coverage.
                        - Completed, failed, cancelled, or absent AgentRuns provide zero active coverage.
                        - Plane assignments, commits, pull requests, Mattermost messages, documentation, and other
                          project evidence never establish active AgentRun coverage.

                        STAFFING CONTRACT:
                        - Before the shared foundation is ready, Project Manager main-routine coverage owns creating it.
                        - Once FOUNDATION READY is true, Product Owner, Project Manager, and Application Developer main-routine
                          coverage are all useful while the mission remains unfinished. Do not wait for product work items,
                          implementation children, commits, tests, or application code before starting missing PO/DEV coverage.
                        - Product Owner creates product slices; therefore product-work absence can never be a prerequisite for
                          starting Product Owner.
                        - Application Developer consumes PM-assigned implementation children and will truthfully no-op when none
                          is ready; an empty source repository is not a reason to withhold Developer coverage after foundation readiness.
                        - If a previously useful run fails or completes while its role still has unfinished mission work,
                          replace that coverage.

                        STARTING RUNS:
                        - Prefer full main routines.
                        - Avoid duplicate active coverage.
                        - Start no more runs than remaining capacity.
                        - Give each new run responsibility for the whole mission through that role, with only concise
                          current-mission context needed to resume.
                        - Return {"agentRuns":[]} only when current useful coverage is sufficient for the readiness state above.
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

        from("direct:director-integration-loop")
                .routeId("director-integration-loop")
                .setProperty("directorProductiveIterations", constant(0))
                .setProperty("directorMadeProgress", constant(true))
                .loopDoWhile(simple("${exchangeProperty.directorMadeProgress} == true && ${exchangeProperty.directorProductiveIterations} < 6"))
                    .setProperty("directorProductiveIterations", simple("${exchangeProperty.directorProductiveIterations++}"))
                    .setProperty("directorMadeProgress", constant(false))
                    .setProperty("decision.director-has-integration-candidate.result", constant(false))
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Read a small fresh current-mission slice sufficient to find at most one In Progress
                                    implementation child carrying a current PM INTEGRATION READY handoff. Plane state and
                                    comments are authoritative for the handoff. Capture the linked pull request and the handoff's
                                    repository, reviewed head, reviewed base, reviewed time, validation, and acceptance reference.
                                    Do not mutate anything. If no eligible handoff exists, stop after the smallest read.
                                    """))
                    .to("tack:act?id=director-workspace-review&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is exactly one current In Progress implementation child selected with a complete current
                                    PM INTEGRATION READY handoff and linked pull request? Return false when no eligible candidate
                                    was found or required handoff fields are missing or ambiguous.
                                    """))
                    .to("tack:decide?id=director-has-integration-candidate")
                    .choice()
                        .when(exchangeProperty("decision.director-has-integration-candidate.result").isEqualTo(true))
                            .setHeader(TackHeaders.PROMPT, constant("""
                                            Own one final pull-request merge action for the team.

                                            Freshly reread the selected implementation child's current Plane comments. A current
                                            INTEGRATION READY comment from Project Management is the authoritative approval; do not
                                            require or infer PM approval from a Gitea review or comment.

                                            Then freshly inspect that exact pull request in Gitea. Merge only when:
                                            - the Plane handoff is still current and contains repository, PR, reviewedHead,
                                              reviewedBase, reviewedAt, validation, and acceptance reference
                                            - the PR is still open and targets the reviewed repository/base
                                            - the current PR head exactly equals reviewedHead
                                            - the PR is mergeable and not conflicted
                                            - no newer commit, review, or material PR comment after reviewedAt has changed the review surface
                                            - current validation evidence does not contradict the PM review

                                            If the head/base/review surface changed, the PR conflicts, or any evidence is ambiguous,
                                            make no source-control mutation. Project Management owns redisposition through Plane.
                                            """))
                            .to("tack:act?id=director-integration-merge&toolProfile=" + IntegrationToolProfile.ID)
                            .choice()
                                .when(exchangeProperty("act.director-integration-merge.durableProgress").isEqualTo(true))
                                    .setProperty("directorMadeProgress", constant(true))
                            .endChoice()
                    .end()
                .end();
    }
}
