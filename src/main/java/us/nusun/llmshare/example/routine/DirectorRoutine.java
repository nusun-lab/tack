package us.nusun.llmshare.example.routine;

import com.fasterxml.jackson.databind.JsonNode;
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
                .process(routine.create("initial-agent-runs")
                        .schemaId("start-agent-run-command-list")
                        .schema("classpath:schemas/example/software-team/start-agent-run-commands-schema.json")
                        .prompt(simple("""
                                Given the mission, propose the initial software-team
                                agent runs to start as generic StartAgentRunCommand
                                objects.

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
                                setup dependencies. Return only schema-compliant JSON.
                                """)))
                .to("direct:create")
                .split().method(DirectorRoutine.class, "agentRunItems")
                    .to("direct:start-agent-run")
                .end()
                .process(routine.exitThought("director-planning"));
    }

    public static java.util.List<JsonNode> agentRunItems(JsonNode root) {
        java.util.ArrayList<JsonNode> rows = new java.util.ArrayList<>();
        if (root != null && root.has("agentRuns") && root.get("agentRuns").isArray()) {
            root.get("agentRuns").forEach(rows::add);
        }
        return rows;
    }
}
