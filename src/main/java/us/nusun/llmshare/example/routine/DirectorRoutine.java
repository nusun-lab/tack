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

@ApplicationScoped
public class DirectorRoutine extends RouteBuilder implements RoutineProvider {
    public static final String ID = "software-team/director";
    public static final String DIRECTOR_ROUTINE_THOUGHT = "Durable orchestration anchors: mission goal, roster by status, active/completed/failed coverage, verified project state, persistent staffing blockers, last provisioning decision.";
    public static final String DIRECTOR_CYCLE_THOUGHT = "Current orchestration cycle: roster statuses, source/project/chat signals, completed/failed run implications, staffing gap, planned AgentRunRequests or no-op decision. Promote durable staffing state to orchestration root before cycle exits.";

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
                .defaultToolRuntime("tool-high")
                .defaultSynthesisRuntime("synthesis-high")
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
                    .process(routine.act("director-source-control-review")
                            .prompt("""
                                    Use source-control tools to read durable documentation and repositories.
                                    Observe only, do not make changes.
                                    """)
                            .toolProfile("source-control"))
                    .to("direct:act")
                    .process(routine.act("director-project-management-review")
                            .prompt("""
                                    Use project-management tools to read mission-related projects.
                                    Observe only, do not make changes.
                                    """)
                            .toolProfile("project-management"))
                    .to("direct:act")
                    .process(routine.act("director-team-chat-review")
                            .prompt("""
                                    Use team-chat tools to inspect recent relevant channel context.
                                    Observe only, do not make changes.
                                    """)
                            .toolProfile("team-chat"))
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
                                Given the mission, tool-backed project state, active thoughts, and mission roster,
                                decide whether to schedule zero or more AgentRunRequest objects.

                                Mission:
                                ${exchangeProperty.mission.goal}

                                Assignable personas:
                                ${bean:personaCatalog.agentRunStartAssignablePromptJson}

                                Routines:
                                ${bean:routineCatalog.startableJson}

                                Tool profiles:
                                ${bean:toolProfileCatalog.promptJson}

                                Choose personas and routines from the catalog data above.
                                - Return {"agentRuns":[]} when no new teammate is needed now.
                                - Do not schedule the same personaId/routineId pair as the roster entry marked self:true.
                                - Treat accepted and running roster entries as active coverage.
                                - Treat complete runs as finished work; add a follow-on only if the project now
                                  needs a different catalog-listed capability or next stage.
                                - Treat failed runs as recovery signals; add a focused replacement or support run
                                  only when the mission still needs that work.
                                - Do not duplicate active persona/routine coverage for the same need.
                                - Keep the team small. Bigger teams share inference lanes and reduce individual
                                  productivity; a larger team does not automatically finish faster.
                                - Schedule at most two new runs in one cycle unless the roster has no active
                                  non-self teammates.
                                - Make each run input clear about the mission focus, why this run is needed now,
                                  and which roster or tool-backed project state it responds to.
                                - Use catalog descriptions and observed state only; these instructions intentionally
                                  do not provide role or routine-name hints.

                                """)))
                .to("direct:create")
                .marshal().json(JsonLibrary.Jackson)
                .unmarshal().json(JsonLibrary.Jackson, MissionRequest.class)
                .setBody(simple("${body.agentRuns}"))
                .to("direct:start-agent-runs")
                .end();
    }
}
