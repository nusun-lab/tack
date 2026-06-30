package us.nusun.llmshare.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.framework.inventory.RoutineProvider;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.routine.RoutineSupport;

import java.util.List;

import static org.apache.camel.builder.Builder.simple;


@ApplicationScoped
public class DirectorRoutine extends RouteBuilder implements RoutineProvider {
    public static final String ID = "software-team/director";

    @Inject RoutineSupport routine;

    @Override
    public List<RoutineDefinition> routines() {
        return List.of(routine());
    }

    public RoutineDefinition routine() {
        return RoutineDefinition.builder(ID)
                .name("Director")
                .description("""
                        Create initial mission-bound software-team agent runs from
                        available personas and routines.
                        """)
                .routeUri("direct:example-software-team-director")
                .defaultToolRuntime("tool-low")
                .defaultSynthesisRuntime("synthesis-low")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-software-team-director")
                .routeId("example-software-team-director")
                .process(routine.enterThought("director-planning", """
                        Director planning context: mission interpretation,
                        useful software-team personas, routine choices,
                        collaboration setup dependencies, blockers, and
                        orchestration state.
                        """, 900))
                .process(routine.create("planned-agent-runs")
                        .schemaId("agent-run-request-list")
                        .schema("classpath:schemas/example/software-team/agent-run-requests-schema.json")
                        .prompt(simple("""
                                Given the mission, propose the initial software-team
                                agent runs to schedule as generic AgentRunRequest
                                objects. Include only personaId, routineId, and
                                optional input. The framework supplies mission id,
                                agent run id, parent lineage, status, and timestamps.

                                Mission:
                                ${exchangeProperty.mission.goal}

                                Personas:
                                ${bean:personaCatalog.agentRunStartAssignableJson}

                                Routines:
                                ${bean:routineCatalog.startableJson}

                                Tool profiles:
                                ${bean:toolProfileCatalog.allJson}

                                Choose personas and routines from the catalog data above.
                                Prefer a small set that makes concrete progress and respects
                                setup dependencies. Scheduling is fire-and-forget; do not
                                assume one agent waits for another. Return only schema-compliant JSON.
                                """)))
                .to("direct:create")
                .split().jsonpath("$.agentRuns[*]")
                    .to("direct:start-agent-run")
                .end()
                .process(routine.exitThought("director-planning"));
    }
}
