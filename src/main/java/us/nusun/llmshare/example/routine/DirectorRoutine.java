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
                .process(routine.enterThought("orchestration-root", """
                        Durable orchestration anchors: mission goal, roster by status, active/completed/failed coverage, verified project state, persistent staffing blockers, last provisioning decision.
                        """, 500))
                .to("direct:director-monitoring-loop")
                .process(routine.exitThought("orchestration-root"));

        from("direct:director-monitoring-loop")
                .routeId("director-monitoring-loop")
                .setProperty("directorCycle", constant(0))
                .setProperty("decision.director-mission-complete.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.director-mission-complete.result").isNotEqualTo(true))
                    .setProperty("directorCycle", simple("${exchangeProperty.directorCycle++}"))
                    .process(routine.enterThought("orchestration-cycle", """
                        Current orchestration cycle: roster statuses, workspace signals, completed/failed run implications, staffing gap, planned AgentRunRequests or no-op decision. Promote durable staffing state to orchestration root before cycle exits.
                        """, 300))
                    .process(routine.act("director-workspace-review")
                            .prompt("""
                                    Read only the smallest mission-relevant status slice.
                                    Check roster-linked Plane work, one direct Gitea/doc anchor, and recent Mattermost context only
                                    when it changes staffing decisions. Do not mutate anything, sweep whole systems, or chase
                                    unrelated artifacts. Stop once completion state or a specific staffing gap is clear.
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
                                Decide whether this mission needs additional agent runs.

                                Mission:
                                ${exchangeProperty.mission.goal}

                                Assignable personas:
                                ${bean:personaCatalog.agentRunStartAssignablePromptJson}

                                Routines:
                                ${bean:routineCatalog.startableJson}

                                Tool profiles:
                                ${bean:toolProfileCatalog.promptJson}

                                Use only catalog entries above and verified current roster/state.
                                Return {"agentRuns":[]} unless a mission-required capability is missing from accepted/running coverage.
                                Treat accepted and running roster entries as active coverage, including blocked agents unless a distinct
                                catalog capability is required to unblock them. Do not schedule work that an active agent already owns.
                                At most two new runs per cycle.

                                For each proposed run, make the input name the mission focus, verified gap, why active coverage cannot
                                handle it, and the distinct capability or execution stage added.

                                """)))
                .to("direct:create")
                .unmarshal().json(JsonLibrary.Jackson, MissionRequest.class)
                .setBody(simple("${body.agentRuns}"))
                .to("direct:start-agent-runs")
                .end();
    }
}
