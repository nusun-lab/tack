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
    public List<RoutineDefinition> routines() { return List.of(routine()); }

    public RoutineDefinition routine() {
        return RoutineDefinition.builder(ID)
                .name("Director")
                .main("Full Director routine: understand current mission progress, maintain useful team coverage, encourage collaboration, merge delivery-approved pull requests, and keep the mission moving until durable evidence shows the entire mission is complete.", "direct:example-software-team-director")
                .defaultLane("high-pass")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-software-team-director")
                .routeId("example-software-team-director")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Durable orchestration anchors: mission goal, authoritative active roster coverage, verified current-mission project basics, blockers, major handoffs, and last focused outcome."))
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
                            Mission: ${exchangeProperty.mission.goal}
                            AUTHORITATIVE ACTIVE COVERAGE: ${exchangeProperty.directorActiveMissionRoster}
                            Capacity: ${exchangeProperty.directorActiveRunCount}/${exchangeProperty.agentRunCapacityMaximum} active, ${exchangeProperty.directorRemainingRunCapacity} remaining.
                            Allowed focus values, in priority order when genuinely actionable:
                            - integration: an open PM-approved integration candidate may exist; route here promptly. Deep PR/lease validation belongs inside this focus.
                            - staffing: required active main-routine coverage appears missing, foundation readiness needs staffing evaluation, or a required run may need replacement.
                            - guidance: a fresh coordination exchange or blocker would materially benefit from Director participation.
                            - completion: durable evidence makes mission completion plausible enough for the dedicated completion check.
                            - wait: none is presently useful; avoid ceremonial polling or duplicate activity.
                            External artifacts never establish active AgentRun coverage; the mission roster is authoritative. Return only the focus object.
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
                        .otherwise()
                            .delay(120000)
                    .end()
                .end()
                .end()
                .to("tack:thought-exit?name=orchestration-root");

        from("direct:director-orientation")
                .routeId("director-orientation")
                .setHeader(TackHeaders.PROMPT, constant("Read the smallest fresh current-mission slice needed to rank the Director's next focus: possible current PM INTEGRATION READY/open PR signal, project-basics signal relevant to staffing, one fresh coordination/blocker signal, and whether durable state plausibly approaches completion. Do not deeply validate a PR here. Do not infer active AgentRuns from project artifacts. Do not mutate."))
                .to("tack:act?id=director-orientation&toolProfile=" + WorkspaceReadonlyToolProfile.ID);

        from("direct:director-guidance-focus")
                .routeId("director-guidance-focus")
                .setHeader(TackHeaders.PROMPT, constant("Read recent current-mission project chat. Participate only when useful to coordinate, encourage, clarify, or unblock real team work. Do not prescribe another role's internal workflow, duplicate durable status, or use chat activity as active-run evidence."))
                .to("tack:act?id=director-team-guidance&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:director-staffing-focus")
                .routeId("director-staffing-focus")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Freshly verify only the shared project basics needed for staffing this mission: one current-mission Plane project,
                        one source repository under agent-workspace, one documentation repository with usable navigation, and one accessible
                        current-mission team-chat channel. An empty source repository is valid. Do not require not-yet-running PO/DEV roles
                        to already be members everywhere; PM owns membership alignment. Product work, implementation children, commits,
                        tests, PRs, and application code are not project-basics prerequisites. Do not mutate.
                        """))
                .to("tack:act?id=director-staffing-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("Is the shared delivery foundation usable for PO and Developer work? Return true only when Plane project, source repository, documentation/navigation, and team chat are verified and readable. An empty source repository is valid; do not require future PO/DEV membership or work/code existence."))
                .to("tack:decide?id=director-foundation-ready")
                .setHeader(TackHeaders.PROMPT, simple("""
                    Decide which AgentRuns are required now.
                    Mission: ${exchangeProperty.mission.goal}
                    Assignable personas: ${bean:personaCatalog.agentRunStartAssignablePromptJson}
                    Available routines: ${bean:routineCatalog.startableJson}
                    AUTHORITATIVE ACTIVE COVERAGE: ${exchangeProperty.directorActiveMissionRoster}
                    FOUNDATION READY: ${exchangeProperty.decision.director-foundation-ready.result}
                    Capacity: ${exchangeProperty.directorActiveRunCount}/${exchangeProperty.agentRunCapacityMaximum} active, ${exchangeProperty.directorRemainingRunCapacity} remaining.
                    The mission roster is the only source of truth for active AgentRun coverage. Only accepted/scheduled/running main-routine AgentRuns count. If foundation is false, required active main-routine coverage is exactly PM. If foundation is true and mission unfinished, required coverage is exactly PO, PM, DEV. Never duplicate an already-active required role. Create one run per missing required role subject to remaining capacity. Return {"agentRuns":[]} when none is missing or capacity prevents another start.
                    """))
                .to("tack:create?id=planned-agent-runs&schemaId=agent-run-request-list&schema=classpath:schemas/example/software-team/agent-run-requests-schema.json")
                .unmarshal().json(JsonLibrary.Jackson, MissionRequest.class)
                .setBody(simple("${body.agentRuns}"))
                .to("direct:start-agent-runs");

        from("direct:director-completion-focus")
                .routeId("director-completion-focus")
                .setHeader(TackHeaders.PROMPT, constant("Review current mission completion once using the smallest authoritative read. One verified unfinished required outcome is enough to conclude not complete. Do not mutate or perform another role's work."))
                .to("tack:act?id=director-mission-status-review&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("Is the top-level mission 100% complete? Return true only when durable current-mission evidence shows every required outcome fully satisfied. Return false for anything incomplete, unclear, blocked, merely planned, waiting, or lacking verified evidence."))
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
                            Read a small fresh current-mission slice sufficient to find at most one In Progress implementation child
                            carrying a current PM INTEGRATION READY handoff whose exact linked PR is still OPEN. Plane state/comments
                            are authoritative for approval; Gitea is authoritative for PR state. Capture repository, PR, reviewedHead,
                            reviewedBaseRef, reviewedBaseSha, reviewedAt, validation, and acceptance reference. A complete current lease
                            pins both reviewed head and exact reviewed base SHA. Closed/merged PRs are not candidates. Do not mutate.
                            """))
                    .to("tack:act?id=director-workspace-review&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("Is exactly one current In Progress implementation child selected with a complete current PM INTEGRATION READY handoff and exact linked PR still OPEN? Require repository, PR, reviewedHead, reviewedBaseRef, reviewedBaseSha, reviewedAt, validation, acceptanceRef."))
                    .to("tack:decide?id=director-has-integration-candidate")
                    .choice()
                        .when(exchangeProperty("decision.director-has-integration-candidate.result").isEqualTo(true))
                            .setHeader(TackHeaders.PROMPT, constant("""
                                    Own one final pull-request merge action. Freshly reread the selected child's current Plane comments,
                                    then the exact PR and current base branch head. Merge only when the current INTEGRATION READY lease is
                                    complete and current, PR open and targeting reviewedBaseRef, PR head equals reviewedHead, current base
                                    commit equals reviewedBaseSha, PR mergeable/non-conflicted, no newer material review-surface change exists,
                                    and validation does not contradict PM review. Any base/head/lease drift invalidates approval. If ambiguous,
                                    closed, merged, stale, or conflicted, make no source mutation; PM owns redisposition.
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
