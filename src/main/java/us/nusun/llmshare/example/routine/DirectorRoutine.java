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
                        verified current-mission project basics, blockers, major handoffs, and last focused outcome.
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
                    .to("direct:director-orientation")
                    .setHeader(TackHeaders.PROMPT, simple("""
                            Choose exactly one highest-priority Director focus for the next focused pass.

                            Mission:
                            ${exchangeProperty.mission.goal}

                            AUTHORITATIVE ACTIVE COVERAGE:
                            ${exchangeProperty.directorActiveMissionRoster}

                            Capacity: ${exchangeProperty.directorActiveRunCount}/${exchangeProperty.agentRunCapacityMaximum} active,
                            ${exchangeProperty.directorRemainingRunCapacity} remaining.

                            Allowed focus values, in priority order when genuinely actionable:
                            - integration: current evidence suggests an open PM-approved integration candidate may exist. Choose this
                              promptly so reviewed PRs do not sit while development moves ahead. Deep PR/lease validation belongs
                              inside the integration focus, not this router.
                            - staffing: required active main-routine coverage appears missing, the shared foundation may need staffing
                              evaluation, or a previously required run completed/failed/cancelled and may need replacement.
                            - guidance: a fresh coordination exchange or blocker would materially benefit from Director participation.
                            - completion: durable evidence makes mission completion plausible enough to perform the dedicated completion check.
                            - wait: none of the above is presently useful; avoid ceremonial polling or duplicate activity.

                            Use the latest root thought and orientation observation. External project artifacts never establish active
                            AgentRun coverage; the mission roster above is authoritative for coverage. Return only the focus object.
                            """))
                    .to("tack:create?id=director-priority-focus&schemaId=director-priority-focus&schema=classpath:schemas/example/software-team/director-priority-focus-schema.json")
                    .unmarshal().json(JsonLibrary.Jackson, PriorityFocus.class)
                    .process(exchange -> exchange.setProperty("directorPriorityFocus", exchange.getMessage().getBody(PriorityFocus.class).focus()))
                    .choice()
                        .when(exchangeProperty("directorPriorityFocus").isEqualTo("integration"))
                            .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Focused integration work: current PM integration lease, exact open PR, reviewed head/base commits, merge safety, and one resulting integration outcome."))
                            .to("tack:thought-enter?name=director-integration-focus&maxTokens=350")
                            .to("direct:director-integration-loop")
                            .to("tack:thought-exit?name=director-integration-focus")
                        .when(exchangeProperty("directorPriorityFocus").isEqualTo("staffing"))
                            .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Focused staffing work: shared-foundation readiness, authoritative active roster coverage, missing required roles, and remaining mission capacity."))
                            .to("tack:thought-enter?name=director-staffing-focus&maxTokens=350")
                            .to("direct:director-staffing-focus")
                            .to("tack:thought-exit?name=director-staffing-focus")
                        .when(exchangeProperty("directorPriorityFocus").isEqualTo("guidance"))
                            .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Focused Director coordination: one current conversation, blocker, clarification, or unblock opportunity that merits Director participation."))
                            .to("tack:thought-enter?name=director-guidance-focus&maxTokens=300")
                            .to("direct:director-guidance-focus")
                            .to("tack:thought-exit?name=director-guidance-focus")
                        .when(exchangeProperty("directorPriorityFocus").isEqualTo("completion"))
                            .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Focused mission completion evaluation using minimal authoritative current-mission evidence."))
                            .to("tack:thought-enter?name=director-completion-focus&maxTokens=300")
                            .to("direct:director-completion-focus")
                            .to("tack:thought-exit?name=director-completion-focus")
                            .filter(exchangeProperty("decision.director-mission-complete.result").isNotEqualTo(true))
                                .delay(120000)
                            .end()
                        .otherwise()
                            .delay(120000)
                    .end()
                .end()
                .end()
                .to("tack:thought-exit?name=orchestration-root");

        from("direct:director-orientation")
                .routeId("director-orientation")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read the smallest fresh current-mission slice needed to rank the Director's next focus. Look only for:
                        - a signal that a current PM INTEGRATION READY handoff may point to an open PR;
                        - current shared-foundation/project-basics state relevant to staffing;
                        - one fresh coordination/blocker signal that may need Director participation; and
                        - whether durable project state plausibly approaches mission completion.

                        Do not deeply review or validate a PR here; the integration focus owns that work. Do not infer active
                        AgentRun coverage from Plane, Gitea, Mattermost, documentation, assignments, or commits. Do not mutate.
                        Stop as soon as the priority signals are sufficiently clear for routing.
                        """))
                .to("tack:act?id=director-orientation&toolProfile=" + WorkspaceReadonlyToolProfile.ID);

        from("direct:director-guidance-focus")
                .routeId("director-guidance-focus")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read recent current-mission project chat. Participate only when useful to coordinate,
                        encourage, clarify, or unblock real team work. Do not prescribe another role's internal
                        workflow, duplicate durable status, or use chat activity as evidence that a teammate is
                        actively running. If no verified current-mission channel is known, skip chat and stop.
                        """))
                .to("tack:act?id=director-team-guidance&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:director-staffing-focus")
                .routeId("director-staffing-focus")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Freshly verify only the shared project basics needed for staffing this mission.
                        Determine whether there is one usable current-mission Plane project, one source repository
                        under agent-workspace, one documentation repository with usable navigation, and one
                        current-mission team-chat channel that this Director run can access. Repository existence
                        is sufficient: the source repository may legitimately have zero commits before Application
                        Development starts.

                        Do not require proof that not-yet-running PO/DEV roles are already members of every shared
                        resource. Project Management owns membership alignment as the mission roster changes.
                        Product work items, implementation children, source commits, tests, pull requests, and
                        application code are NOT project-basics prerequisites. Their absence must never block
                        Product Owner or Application Developer staffing once the shared foundation itself exists.
                        Do not mutate anything. Stop after the smallest authoritative reads needed to decide.
                        """))
                .to("tack:act?id=director-staffing-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Is the shared delivery foundation currently usable for Product Owner and Application Developer work?
                        Return true only when Plane project, source repository, documentation/navigation, and team chat
                        are verified and readable from this Director run. An empty source repository is valid.
                        Do not require proof of future PO/DEV membership. Work-item or code absence does not make
                        the foundation incomplete.
                        """))
                .to("tack:decide?id=director-foundation-ready")
                .setHeader(TackHeaders.PROMPT, simple("""
                    Decide which AgentRuns are required now.

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

                    REQUIRED COVERAGE:
                    - If FOUNDATION READY is false, the required active main-routine coverage set is exactly:
                      Project Manager.
                    - If FOUNDATION READY is true and the mission is unfinished, the required active main-routine
                      coverage set is exactly: Product Owner, Project Manager, and Application Developer.
                    - Once FOUNDATION READY is true, do not wait for product work items, implementation children,
                      commits, tests, or application code before filling missing Product Owner or Application Developer coverage.
                    - Product Owner creates product slices; therefore product-work absence can never be a prerequisite for
                      starting Product Owner.
                    - Application Developer consumes PM-assigned implementation children and will truthfully no-op when none
                      is ready; an empty source repository is not a reason to withhold Developer coverage after foundation readiness.

                    MISSING-COVERAGE RULE:
                    - Compute missing coverage as REQUIRED COVERAGE minus AUTHORITATIVE ACTIVE COVERAGE.
                    - Match coverage by required role and its full main routine. An accepted, scheduled, or running main-routine
                      AgentRun for a required role means that role is PRESENT, not missing.
                    - Emit AgentRuns ONLY for roles in the missing-coverage set.
                    - NEVER emit a second AgentRun for a role that already has accepted, scheduled, or running main-routine coverage,
                      even when there is additional work, a new handoff, idle capacity, or another useful task for that role.
                    - One active full main-routine AgentRun owns that role's whole-mission responsibility. Additional work is handled
                      inside that run, not by duplicate staffing.
                    - If a previously required run completes, fails, or is cancelled while that role remains required, it is no longer
                      active coverage and may be replaced on a later staffing cycle.

                    STARTING RUNS:
                    - Create one AgentRun for each missing required role, subject only to remaining capacity.
                    - Prefer full main routines; required coverage is main-routine coverage, not subroutine coverage.
                    - Start no more runs than remaining capacity.
                    - Give each new run responsibility for the whole mission through that role, with only concise
                      current-mission context needed to resume.
                    - Return {"agentRuns":[]} when the missing-coverage set is empty or capacity prevents another start.
                    """))
                .to("tack:create?id=planned-agent-runs&schemaId=agent-run-request-list&schema=classpath:schemas/example/software-team/agent-run-requests-schema.json")
                .unmarshal().json(JsonLibrary.Jackson, MissionRequest.class)
                .setBody(simple("${body.agentRuns}"))
                .to("direct:start-agent-runs");

        from("direct:director-completion-focus")
                .routeId("director-completion-focus")
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
                .to("tack:decide?id=director-mission-complete");

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
                            implementation child carrying a current PM INTEGRATION READY handoff whose exact linked
                            pull request is still OPEN. Plane state/comments are authoritative for approval; Gitea is
                            authoritative for pull-request state. Capture repository, PR, reviewedHead, reviewedBaseRef,
                            reviewedBaseSha, reviewedAt, validation, and acceptance reference.

                            A complete current integration lease must pin both the reviewed head commit and the exact
                            reviewed base commit SHA. A branch name alone is insufficient. A merged or otherwise closed
                            pull request is not an eligible Director integration candidate, even if Plane still says
                            In Progress with a current INTEGRATION READY comment. Project Management owns post-merge
                            closure or redisposition of closed-unmerged work. Do not mutate. If no eligible open handoff
                            exists, stop after the smallest read.
                            """))
                    .to("tack:act?id=director-workspace-review&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Is exactly one current In Progress implementation child selected with a complete current
                            PM INTEGRATION READY handoff and an exact linked pull request that is still OPEN?
                            Return true only when repository, PR, reviewedHead, reviewedBaseRef, reviewedBaseSha,
                            reviewedAt, validation, and acceptanceRef are present and unambiguous. Return false when
                            no eligible candidate was found, the linked PR is merged/closed, or any required lease field is missing.
                            """))
                    .to("tack:decide?id=director-has-integration-candidate")
                    .choice()
                        .when(exchangeProperty("decision.director-has-integration-candidate.result").isEqualTo(true))
                            .setHeader(TackHeaders.PROMPT, constant("""
                                    Own one final pull-request merge action for the team.

                                    Freshly reread the selected implementation child's current Plane comments. A current
                                    INTEGRATION READY comment from Project Management is the authoritative approval; do not
                                    require or infer PM approval from a Gitea review or comment.

                                    Then freshly inspect that exact pull request and its current base branch head in Gitea.
                                    Merge only when:
                                    - the Plane handoff is still current and contains repository, PR, reviewedHead,
                                      reviewedBaseRef, reviewedBaseSha, reviewedAt, validation, and acceptanceRef
                                    - the PR is still open and targets reviewedBaseRef
                                    - the current PR head exactly equals reviewedHead
                                    - the current commit at reviewedBaseRef exactly equals reviewedBaseSha
                                    - the PR is mergeable and not conflicted
                                    - no newer commit, review, or material PR comment after reviewedAt has changed the review surface
                                    - current validation evidence does not contradict the PM review

                                    Any change to the base branch commit SHA invalidates the approval even when the base branch
                                    name is unchanged and the PR remains mergeable. If the PR is already merged/closed, head/base
                                    lease changed, the PR conflicts, or any evidence is ambiguous, make no source-control mutation.
                                    Project Management owns closure or redisposition through Plane and must issue fresh review evidence.
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
