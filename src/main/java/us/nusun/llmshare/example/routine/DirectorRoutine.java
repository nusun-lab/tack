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
                        Full Director routine: self-directed mission orchestration cycle. Monitor active capacity,
                        inspect only mission-relevant state, lead the team through concise chat guidance, create focused
                        AgentRunRequests when useful, evaluate completion, and wait between cycles so worker routines can progress.
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
                        verified project state, last provisioning decision.
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
                                Current orchestration cycle: active run coverage, workspace signals, completed/failed run implications, staffing gap, planned AgentRunRequests or no-op decision. Promote durable staffing state to orchestration root before cycle exits.
                                """))
                            .to("tack:thought-enter?name=orchestration-cycle&maxTokens=300")
                            .setHeader(TackHeaders.PROMPT, constant("""
                                            Read only the smallest mission-relevant status slice needed to identify the earliest
                                            unsatisfied delivery-pipeline stage:
                                            1. Collaboration basics missing.
                                            2. Basics exist but no useful PO product slice is ready.
                                            3. A PM HANDOFF: READY product item exists but Owen/PM coverage is missing.
                                            4. An assigned actionable developer task exists but Miles/DEV coverage is missing.
                                            5. PM REVIEW: READY, DEV BLOCKER:, or active-delivery coordination needs Owen/PM.
                                            6. Noah, Owen, Ethan, and Miles full routines are staffed and progressing.

                                            Use durable Plane state as the handoff source of truth. Mattermost is only a signal.
                                            Check direct documentation/project-basics links, one relevant Plane slice, one relevant
                                            Gitea/source anchor, and recent chat only when it changes the next staffing decision.
                                            Do not mutate anything, sweep whole systems, or chase unrelated artifacts.
                                            """))
                            .to("tack:act?id=director-workspace-review&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                            .setHeader(TackHeaders.PROMPT, constant("""
                                            Read recent project Mattermost messages from the project-basics link or default channel.
                                            Lead the team only when concise guidance helps active teammates coordinate around an
                                            already staffed handoff. Stay quiet when the real action is starting a missing consumer role.
                                            Do not use chat reminders as a substitute for starting Owen, Ethan, or Miles.
                                            Do not duplicate Plane or documentation state, create work in chat, or remind a role that is
                                            not actively represented by an accepted, scheduled, or running full routine.
                                            """))
                            .to("tack:act?id=director-team-guidance&toolProfile=" + TeamChatToolProfile.ID)
                            .setHeader(TackHeaders.PROMPT, simple("""
                                Decide whether this mission needs additional agent runs for the delivery pipeline.

                                Mature mission coverage is one active full main routine for each role:
                                Noah/director, Owen/project-manager, Ethan/product-owner, and Miles/application-developer.
                                A completed prior run is not current coverage. Only accepted, scheduled, or running full routines
                                count as active coverage for that role.

                                Mission:
                                ${exchangeProperty.mission.goal}

                                Active capacity:
                                maximum: ${exchangeProperty.agentRunCapacityMaximum}
                                active: ${exchangeProperty.directorActiveRunCount}
                                remaining: ${exchangeProperty.directorRemainingRunCapacity}

                                Current accepted/scheduled/running runs:
                                ${exchangeProperty.directorActiveMissionRoster}

                                Assignable personas:
                                ${bean:personaCatalog.agentRunStartAssignablePromptJson}

                                Available routines and declared subroutines:
                                ${bean:routineCatalog.startableJson}

                                Apply these staffing rules in order:
                                If collaboration basics are missing and Owen's full project-manager routine is not active,
                                start Owen with software-team/project-manager.
                                Do not start Ethan before basics are verified, unless Owen is already active and verified
                                workspace state shows basics are now usable.
                                Once basics are usable, ensure Ethan's full product-owner routine is active when product
                                coverage or a PM-ready slice is missing.
                                If PM HANDOFF: READY exists and Owen is not active, start Owen's full project-manager routine.
                                If an assigned actionable developer task exists and Miles is not active, start Miles's full
                                application-developer routine.
                                If PM REVIEW: READY, DEV BLOCKER:, or another active-delivery coordination need exists and
                                Owen is not active, start Owen's full project-manager routine.
                                Once the first actionable developer task exists, prefer all four personas represented by
                                their full main routines.

                                Do not create a duplicate full run for a role that already has an accepted, scheduled, or
                                running full run. Do not treat an active role as coverage for another role's lifecycle.
                                Prefer full main routines. Do not use subroutines during normal bootstrapping or steady-state
                                delivery unless mission input explicitly requests a narrow subroutine.
                                Do not send chat reminders as a substitute for starting an absent consumer role.
                                Return {"agentRuns":[]} only when the next required pipeline capability is already actively
                                covered or no durable state yet justifies the downstream role.
                                Return no more agent runs than remaining capacity.

                                For each proposed full run, set personaId, routineId, omit subroutineId, and provide focused input
                                naming the exact pipeline stage, durable handoff evidence, and missing consumer capability.
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
