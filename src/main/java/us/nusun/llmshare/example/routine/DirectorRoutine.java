package us.nusun.llmshare.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.framework.inventory.RoutineProvider;
import org.apache.camel.model.dataformat.JsonLibrary;
import us.nusun.llmshare.framework.model.MissionRequest;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.routine.RoutineSupport;

import java.util.List;

import static org.apache.camel.builder.Builder.simple;
import us.nusun.llmshare.example.tool.WorkspaceReadonlyToolProfile;

@ApplicationScoped
public class DirectorRoutine extends RouteBuilder implements RoutineProvider {
    public static final String ID = "software-team/director";
    public static final String DIRECTOR_ROUTINE_THOUGHT = "Durable orchestration anchors: mission goal, roster by status, active/completed/failed coverage, verified project state, persistent staffing blockers, last provisioning decision.";
    public static final String DIRECTOR_CYCLE_THOUGHT = "Current orchestration cycle: roster statuses, workspace signals, completed/failed run implications, staffing gap, planned AgentRunRequests or no-op decision. Promote durable staffing state to orchestration root before cycle exits.";

    @Inject RoutineSupport routine;

    @Override
    public List<RoutineDefinition> routines() {
        return List.of(routine());
    }

    public RoutineDefinition routine() {
        return RoutineDefinition.builder(ID)
                .name("Director")
                .description("""
                        Monitor mission roster and project state, then create
                        focused AgentRunRequest objects when needed.
                        """)
                .routeUri("direct:example-software-team-director")
                .defaultLane("high-pass")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-software-team-director")
                .routeId("example-software-team-director")
                .process(routine.enterThought("orchestration-root", DIRECTOR_ROUTINE_THOUGHT, 500))
                .to("direct:director-monitoring-loop")
                .process(routine.exitThought("orchestration-root"));

        from("direct:director-monitoring-loop")
                .routeId("director-monitoring-loop")
                .setProperty("directorCycle", constant(0))
                .setProperty("decision.director-mission-complete.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.director-mission-complete.result").isNotEqualTo(true))
                    .setProperty("directorCycle", simple("${exchangeProperty.directorCycle++}"))
                    .process(routine.enterThought("orchestration-cycle", DIRECTOR_CYCLE_THOUGHT, 300))
                    .process(routine.act("director-workspace-review")
                            .prompt("""
                                    Read only mission-relevant workspace evidence.
                                    Inspect Plane planned-work state for ownership, status, blockers, and priorities;
                                    Gitea repositories, documentation pages, commits, pull requests, builds, tags, or releases
                                    for implementation evidence; and recent Mattermost messages or threads for conversation context.
                                    Do not mutate anything, do not scan entire projects, repositories, documentation repositories,
                                    or channels, and distinguish verified observations from assumptions.
                                    """)
                            .toolProfile(WorkspaceReadonlyToolProfile.ID))
                    .to("direct:act")
                    .to("direct:director-agent-run-planning")
                    .process(routine.decide("director-mission-complete")
                            .prompt("""
                                    Is the top-level mission 100% complete?
                                    Return true only when durable external evidence shows the mission goal is
                                    fully satisfied, no accepted or running roster entries are still needed,
                                    no failed run requires recovery, and no unresolved blocker remains.
                                    Return false if any required outcome is incomplete, unclear, blocked,
                                    merely planned, waiting for active teammates, or lacking verified evidence.
                                    """))
                    .to("direct:decide")
                    .process(routine.exitThought("orchestration-cycle"))
                .end();


        from("direct:director-agent-run-planning")
                .routeId("director-agent-run-planning")
                .process(routine.create("planned-agent-runs")
                        .schemaId("agent-run-request-list")
                        .schema("classpath:schemas/example/software-team/agent-run-requests-schema.json")
                        .prompt(simple("""
                                Given the mission, verified tool-backed project state, active thoughts, and mission roster,
                                decide whether the mission needs any additional agent runs.
                                
                                Mission:
                                ${exchangeProperty.mission.goal}
                                
                                Assignable personas:
                                ${bean:personaCatalog.agentRunStartAssignablePromptJson}
                                
                                Routines:
                                ${bean:routineCatalog.startableJson}
                                
                                Tool profiles:
                                ${bean:toolProfileCatalog.promptJson}
                                
                                Choose personas and routines only from the catalog data above.
                                
                                An AgentRunRequest provisions additional execution coverage. It is not:
                                
                                * a task assignment for an agent that is already running,
                                * a message or handoff to an existing teammate,
                                * a way to redirect an existing agent toward newly discovered work, or
                                * a replacement for recording actionable work in the appropriate shared system.
                                
                                Before creating any run, determine which mission-required capability is currently absent from the active roster.
                                
                                Apply these rules:
                                
                                * Return {"agentRuns":[]} when no additional active coverage is needed.
                                * Treat accepted and running roster entries as active coverage.
                                * Never schedule a personaId/routineId pair that is already accepted or running merely because there is new work for that agent to perform.
                                * Existing agents are expected to discover and act on relevant shared project state through their own routines.
                                * Work that belongs to an active agent is not a staffing gap.
                                * A blocked active agent is not automatically a staffing gap. Add another run only when a distinct catalog-listed capability is required to resolve the blocker.
                                * Do not schedule the same personaId/routineId pair as the roster entry marked self:true.
                                * Treat complete runs as finished coverage. Create a follow-on only when the verified project state now requires another execution stage or a different capability.
                                * Treat failed runs as recovery signals. Add a replacement or support run only when the mission still requires that coverage and no accepted or running roster entry already provides it.
                                * Use the current mission roster as authoritative for run status and active coverage.
                                * Treat active thoughts as potentially stale planning context. When thoughts conflict with the current roster or verified tool-backed state, prefer the current roster and verified state.
                                * Do not infer a staffing gap from statements such as "no work started," "no code exists," "items are blocked," or "tasks are not actionable" when an active roster member already owns the relevant capability.
                                * Keep the team small. Additional agents share inference lanes and may reduce productivity.
                                * Schedule at most two new runs in one cycle unless there are no active non-self teammates.
                                * For every proposed run, identify the specific missing capability it adds. If no missing capability can be named, do not create the run.
                                * Make each run input clear about:
                                
                                  * the mission focus,
                                  * the verified state requiring additional coverage,
                                  * why the existing active roster cannot cover that need, and
                                  * the distinct capability or execution stage the new run adds.
                                * Use only catalog descriptions and observed state. Do not invent personas, routines, tools, blockers, or external state.
                                
                                Perform the decision in this order:
                                
                                1. Build the set of personaId/routineId pairs whose roster status is accepted or running.
                                2. Identify mission needs supported by verified project state.
                                3. Determine whether each need is already covered by an active pair.
                                4. Discard needs that are merely tasks for existing active agents.
                                5. Create AgentRunRequest objects only for remaining uncovered capabilities.
                                6. Return {"agentRuns":[]} when no uncovered capability remains.

                                """)))
                .to("direct:create")
                .unmarshal().json(JsonLibrary.Jackson, MissionRequest.class)
                .setBody(simple("${body.agentRuns}"))
                .to("direct:start-agent-runs")
                .end();
    }
}
