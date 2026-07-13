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
                                            Read only the smallest mission-relevant status slice.
                                            Check roster-linked Plane work, one direct Gitea/doc anchor, and recent Mattermost context only
                                            when it changes staffing decisions. Do not mutate anything, sweep whole systems, or chase
                                            unrelated artifacts. Stop once completion state or a specific staffing gap is clear.
                                            """))
                            .to("tack:act?id=director-workspace-review&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                            .setHeader(TackHeaders.PROMPT, constant("""
                                            Read recent project Mattermost messages from the project-basics link or default channel.
                                            Lead the team only when a concise coordination note, priority clarification, blocker question,
                                            or handoff reminder would help active teammates. Do not duplicate Plane or documentation state,
                                            do not create work in chat, and stay quiet when the team already has clear direction.
                                            """))
                            .to("tack:act?id=director-team-guidance&toolProfile=" + TeamChatToolProfile.ID)
                            .setHeader(TackHeaders.PROMPT, simple("""
                                Decide whether this mission needs additional agent runs.

                                A full routine run gives a persona broad ownership of its normal lifecycle. The routine description
                                describes that full target. Select the full routine by omitting subroutineId.
                                A subroutine run targets one specific declared stage or responsibility.
                                Agent input explains the exact mission-specific focus for this run.

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

                                Use only catalog entries above and verified current roster/state.
                                Do not create a run whose work is already covered by an accepted, scheduled, or running run.
                                Runs using the same persona are allowed only when their execution targets and focused inputs represent genuinely distinct concurrent work.
                                Prefer an existing full-routine owner when that run can reasonably handle the work.
                                Prefer a full routine when the persona should self-direct across context gathering, execution, communication, and closeout.
                                Use a subroutine only when a specific declared work target must be executed independently.
                                If possible, select the main routines so that agents are continuous and self-directed.
                                Do not create runs for context gathering alone.
                                Return {"agentRuns":[]} when current coverage is sufficient.
                                Return no more agent runs than remaining capacity.

                                For each proposed run, set personaId, routineId, optional subroutineId, and focused input.
                                The input must identify the mission-specific focus, verified gap, why active coverage cannot handle it, and the distinct capability or execution stage added.
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
