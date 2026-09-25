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
import us.nusun.llmshare.framework.tack.TackHeaders;

import java.util.List;

import static org.apache.camel.builder.Builder.simple;

@ApplicationScoped
public class DirectorRoutine extends RouteBuilder implements RoutineProvider {
    public static final String ID = "software-team/director";
    static final int MAX_ACTIVE_MISSION_RUNS = 5;

    @Inject AgentRunCapacityService agentRunCapacityService;

    @Override
    public List<RoutineDefinition> routines() { return List.of(routine()); }

    public RoutineDefinition routine() {
        return RoutineDefinition.builder(ID)
                .name("Director")
                .main("Direct mission execution through completion.", "direct:example-software-team-director")
                .defaultLane("high-pass")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-software-team-director")
                .routeId("example-software-team-director")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Durable orchestration anchors: mission goal, authoritative active roster coverage, verified
                        current-mission project/repo/channel ids, product-backlog presence, open integration work,
                        blockers, major handoffs, and last focused outcome.
                        """))
                .to("tack:thought-enter?name=orchestration-root&maxTokens=500")
                .setProperty("directorCycle", constant(0))
                .setProperty("decision.director-mission-complete.result", constant("no"))
                .setProperty(AgentRunAdmissionService.CAPACITY_MAXIMUM_PROPERTY, constant(MAX_ACTIVE_MISSION_RUNS))
                .loopDoWhile(exchangeProperty("decision.director-mission-complete.result").isNotEqualTo("yes"))
                    .setProperty("directorCycle", simple("${exchangeProperty.directorCycle++}"))
                    .setProperty("directorCapacity", method(agentRunCapacityService, "capacityForCurrentMission"))
                    .setProperty("directorActiveRunCount", simple("${exchangeProperty.directorCapacity.active}"))
                    .setProperty("directorRemainingRunCapacity", simple("${exchangeProperty.directorCapacity.remaining}"))
                    .to("direct:director-orientation")
                    .setHeader(TackHeaders.PROMPT, simple("""
                            Choose exactly one highest-priority Director focus for the next focused
                            pass. Mission: ${exchangeProperty.mission.goal}
                            Use the fresh authoritative active AgentRun roster observed by director-orientation. Do not
                            infer active coverage from Plane, Gitea, Mattermost, or older thought when that fresh roster
                            evidence is available.
                            Capacity: ${exchangeProperty.directorActiveRunCount}/${exchangeProperty.agentRunCapacityMaximum}
                            active, ${exchangeProperty.directorRemainingRunCapacity} remaining.
                            
                            Staffing policy is phase-based and must be applied when ranking focus:
                            - PM is required whenever the unfinished mission lacks an
                              active Project Manager. PM owns bootstrap.
                            - Before the shared Plane/source/docs/chat foundation is
                              usable, PM is the only additional required role.
                            - Once the shared foundation is usable, PO becomes required immediately even when Plane has zero
                              work items. PO creates and maintains the product backlog;
                              never wait for backlog before staffing PO.
                            - Once at least one current-mission product parent/backlog item exists in Plane, Developer
                              becomes required. Do not wait for PM-created implementation children before staffing
                              Developer; PM can decompose while Developer comes online.
                            - A missing role required by the current phase makes staffing genuinely actionable.
                            
                            Allowed focus values, in priority order when genuinely actionable:
                            - integration: an In Progress implementation child has a linked OPEN pull request that may be
                              ready for safe merge. A PM approval comment or named comment state is NOT required. Route here
                              promptly so integration latency stays low.
                            - staffing: evaluate the explicit phase policy above against the authoritative mission roster,
                              shared-foundation signal, and product-backlog signal; route here whenever required active
                              coverage is missing or a required run may need replacement.
                            - guidance: there is a useful coordination message to initiate, or a fresh coordination
                              exchange/blocker would materially benefit from Director participation.
                            - wait: none is presently useful; avoid ceremonial polling or duplicate activity.
                            
                            Fresh orientation evidence outranks older thought conclusions. Use durable thought for anchors,
                            not as authority for a previous focus choice. Do not invent additional staffing, review, or
                            integration prerequisites that are not stated here. External artifacts never establish active
                            AgentRun coverage; the mission roster is authoritative. Choose exactly one allowed focus value.
                            """))
                    .to("tack:decide?id=director-focus&choices=integration,staffing,guidance,wait")
                    .toD("direct:director-focus-${exchangeProperty.decision.director-focus.result}")
                .end()
                .end()
                .to("tack:thought-exit?name=orchestration-root");

        from("direct:director-focus-integration")
                .routeId("director-focus-integration")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Focused integration work: one In Progress implementation child, its exact linked open PR,
                        current head/base, validation evidence, merge safety, and resulting integration outcome.
                        """))
                .to("tack:thought-enter?name=director-integration-focus&maxTokens=350")
                .to("direct:director-integration-loop")
                .to("tack:thought-exit?name=director-integration-focus");

        from("direct:director-focus-staffing")
                .routeId("director-focus-staffing")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Focused staffing work: shared-foundation readiness, product-backlog presence, authoritative
                        active roster coverage, phase-required roles, and remaining mission capacity.
                        """))
                .to("tack:thought-enter?name=director-staffing-focus&maxTokens=350")
                .to("direct:director-staffing-focus")
                .to("tack:thought-exit?name=director-staffing-focus");

        from("direct:director-focus-guidance")
                .routeId("director-focus-guidance")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Focused Director coordination: one useful message to initiate or one current conversation,
                        blocker, clarification, or unblock opportunity that merits Director participation.
                        """))
                .to("tack:thought-enter?name=director-guidance-focus&maxTokens=300")
                .to("direct:director-guidance-focus")
                .to("tack:thought-exit?name=director-guidance-focus");

        from("direct:director-focus-wait")
                .routeId("director-focus-wait")
                .to("direct:director-completion-focus")
                .choice()
                    .when(exchangeProperty("decision.director-mission-complete.result").isNotEqualTo("yes"))
                        .delay(120000)
                .end();

        from("direct:director-orientation")
                .routeId("director-orientation")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Call mission_roster with view=coverage first. Then read the smallest fresh current-mission slice needed to rank the
                        Director's next focus. Capture only: the authoritative current active AgentRun roster; whether
                        an In Progress implementation child has a linked OPEN PR; whether the shared
                        Plane/source/docs/chat foundation appears usable; whether at least one current-mission product
                        parent/backlog item exists in Plane; one fresh coordination/blocker signal or useful message
                        worth initiating. If the current
                        thought already contains verified project/repo/channel ids, use direct lookups by those anchors
                        rather than listing whole workspaces. Report current observations only. Do not recommend a next
                        focus, invent sequencing policy, or preserve an older HOLD/wait conclusion when fresh evidence
                        differs. Do not deeply validate a PR here. Do not infer active
                        AgentRuns from project artifacts. Do not mutate.
                        """))
                .to("tack:act?id=director-orientation&toolProfile=" + WorkspaceReadonlyToolProfile.ID);

        from("direct:director-guidance-focus")
                .routeId("director-guidance-focus")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read only recent current-mission project chat. Do not require an existing conversation before
                        posting. Initiate one concise message when a meaningful merge, blocker, staffing/foundation
                        transition, question, clarification, or unblock notification would help a current teammate act
                        sooner; otherwise reply to one fresh useful exchange. Avoid generic status and duplicate durable
                        state. Plane and Gitea remain authoritative.
                        """))
                .to("tack:act?id=director-team-guidance&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:director-staffing-focus")
                .routeId("director-staffing-focus")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Call mission_roster with view=coverage first and capture the authoritative current active AgentRun coverage.
                        Then freshly verify only the staffing phase signals for this mission: one current-mission Plane
                        project, one source repository under agent-workspace, one documentation repository with usable
                        navigation, one accessible current-mission team-chat channel, and whether at least one
                        current-mission PRODUCT PARENT/backlog item exists in Plane. Prefer direct lookup using verified
                        ids already present in current thought. An empty source repository is valid. Product backlog
                        existence is only an existence signal; do not require implementation children, commits, tests,
                        PRs, or application code. Do not require not-yet-running PO/DEV roles to already be members
                        everywhere; PM owns membership alignment. Do not mutate.
                        """))
                .to("tack:act?id=director-staffing-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Is the shared delivery foundation usable for Product Owner and later Developer work? Answer yes
                        only when Plane project, source repository, documentation/navigation, and team chat are verified
                        and readable. An empty source repository is valid; do not require
                        future PO/DEV membership or work/code existence.
                        """))
                .to("tack:decide?id=director-foundation-ready")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Does at least one current-mission product parent/backlog item exist in Plane? Answer yes for any
                        verified current product-parent work item regardless of lifecycle state. Answer no when no such
                        product item exists or the evidence is missing. Do not require implementation children.
                        """))
                .to("tack:decide?id=director-product-backlog-exists")
                .setHeader(TackHeaders.PROMPT, simple("""
                    Decide which AgentRuns are required now. Mission: ${exchangeProperty.mission.goal} Assignable
                    personas: ${bean:personaCatalog.agentRunStartAssignablePromptJson} Available
                    routines: ${bean:routineCatalog.startableJson}
                    Use the authoritative current active AgentRun roster captured by director-staffing-read in the
                    active thought. FOUNDATION READY: ${exchangeProperty.decision.director-foundation-ready.result}
                    PRODUCT BACKLOG EXISTS: ${exchangeProperty.decision.director-product-backlog-exists.result}
                    Capacity: ${exchangeProperty.directorActiveRunCount}/${exchangeProperty.agentRunCapacityMaximum}
                    active, ${exchangeProperty.directorRemainingRunCapacity} remaining. The mission roster is the
                    only source of truth for active AgentRun coverage. Only accepted/scheduled/running main-routine
                    AgentRuns count. Phase policy for an unfinished mission:
                    - PM is always required. If foundation is no, required active coverage is PM only.
                    - If foundation is yes and product backlog does not yet exist, required active coverage is PM +
                      PO. PO must be started even with zero work items because PO populates the product backlog.
                    - If foundation is yes and product backlog exists, required active coverage is PM + PO + DEV.
                      Developer should be started once product backlog exists; do not wait for implementation
                      children. Never duplicate an already-active required role. Create one run per missing required
                      role subject to remaining capacity. Return {"agentRuns":[]} when none is
                      missing or capacity prevents another start.
                    """))
                .to("tack:create?id=planned-agent-runs&schemaId=agent-run-request-list&schema=classpath:schemas/example/software-team/agent-run-requests-schema.json")
                .unmarshal().json(JsonLibrary.Jackson, MissionRequest.class)
                .setBody(simple("${body.agentRuns}"))
                .to("direct:start-agent-runs");

        from("direct:director-completion-focus")
                .routeId("director-completion-focus")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Review current mission completion once using the smallest authoritative read. Prefer known ids.
                        One verified unfinished required outcome is enough to conclude not complete. Do
                        not mutate or perform another role's work.
                        """))
                .to("tack:act?id=director-mission-status-review&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Is the top-level mission 100% complete? Answer yes only when durable current-mission evidence
                        shows every required outcome fully satisfied. Answer no for anything incomplete, unclear,
                        blocked, merely planned, waiting, or lacking verified evidence.
                        """))
                .to("tack:decide?id=director-mission-complete");

        from("direct:director-integration-loop")
                .routeId("director-integration-loop")
                .setProperty("directorProductiveIterations", constant(0))
                .setProperty("directorMadeProgress", constant(true))
                .loopDoWhile(simple("${exchangeProperty.directorMadeProgress} == true && ${exchangeProperty.directorProductiveIterations} < 6"))
                    .setProperty("directorProductiveIterations", simple("${exchangeProperty.directorProductiveIterations++}"))
                    .setProperty("directorMadeProgress", constant(false))
                    .setProperty("decision.director-has-integration-candidate.result", constant("no"))
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Read a small fresh current-mission slice sufficient to find at most one In Progress
                            implementation child with an exact linked PR that is still OPEN. Plane state/assignment and task
                            evidence identify the implementation unit; Gitea is authoritative for PR state. Capture
                            repository, PR, current head/base, changed files, validation, parent/acceptance reference, and
                            any current evidence-backed blocker or review concern. Named comment states such as REVIEW READY
                            or INTEGRATION READY are not required and must not gate integration. Closed/merged PRs are not
                            candidates for merge. Prefer verified ids already present in thought. Do not mutate.
                            """))
                    .to("tack:act?id=director-workspace-review&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Is exactly one current In Progress implementation child selected with an exact linked PR still
                            OPEN and enough task/validation context to perform a safe integration check? Do not
                            require any named PM approval or handoff comment.
                            """))
                    .to("tack:decide?id=director-has-integration-candidate")
                    .choice()
                        .when(exchangeProperty("decision.director-has-integration-candidate.result").isEqualTo("yes"))
                            .setHeader(TackHeaders.PROMPT, constant("""
                                    Own one final pull-request merge action. Freshly read the exact PR and current base branch head
                                    plus only the selected child's evidence needed to judge safety. Merge when the PR is open,
                                    targets the intended base, current head/base are known, the PR is mergeable/non-conflicted,
                                    validation evidence is credible for the task, current implementation satisfies the linked
                                    acceptance scope, and no newer evidence-backed blocker or review concern contradicts merge. Do
                                    not require a PM approval phrase or named comment state. If ambiguous, stale, conflicted,
                                    failing validation, or product intent is unresolved, make no source mutation and leave concise
                                    evidence/context for the responsible role rather than manufacturing workflow state in comments.
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
