package us.nusun.llmshare.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.framework.inventory.RoutineProvider;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.routine.RoutineSupport;

import java.util.List;


@ApplicationScoped
public class ApplicationDeveloperRoutine extends RouteBuilder implements RoutineProvider {
    public static final String ID = "software-team/application-developer";

    @Inject RoutineSupport routine;

    @Override
    public List<RoutineDefinition> routines() {
        return List.of(routine());
    }

    public RoutineDefinition routine() {
        return RoutineDefinition.builder(ID)
                .name("Application Developer")
                .description("""
                        Drive implementation through assignment review, source readiness,
                        delivery, change-review readiness, and closeout notes.
                        """)
                .routeUri("direct:example-software-team-application-developer")
                .defaultToolRuntime("tool-low")
                .defaultSynthesisRuntime("synthesis-low")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-software-team-application-developer")
                .routeId("example-software-team-application-developer")
                .process(routine.enterThought("application-developer-routine", """
                        Developer working context: assigned work, target source
                        repository, tests, implementation decisions, change-review
                        readiness, tool evidence, and technical blockers.
                        """, 1100))
                .setProperty("applicationDeveloperCycle", constant(0))
                .loopDoWhile(simple("""
                        ${exchangeProperty[decision.application-developer-done.result]} != true
                        && ${exchangeProperty.applicationDeveloperCycle} < 3
                        """))
                    .setProperty("applicationDeveloperCycle", simple("${exchangeProperty.applicationDeveloperCycle++}"))
                    .process(routine.enterThought("application-developer-cycle", """
                            Developer loop context: current assigned task, source
                            repository readiness, implementation/test action,
                            change-review readiness, project management updates,
                            and closeout evidence for this cycle.
                            """, 600))
                    .process(routine.act("chat-kickoff")
                            .prompt("""
                                    Post the current implementation focus in the shared
                                    chat channel. Use verified project basics and do not
                                    invent channels.
                                    """)
                            .toolProfile("team-chat"))
                    .to("direct:act")
                    .process(routine.act("collaboration-sync")
                            .prompt("""
                                    Ask one focused implementation question or give one
                                    focused implementation status update in the shared
                                    chat channel.
                                    """)
                            .toolProfile("team-chat"))
                    .to("direct:act")
                    .process(routine.act("assignment-review")
                            .prompt("""
                                    Review one assigned task in the project management
                                    system. Capture assignment, objective, blocker state,
                                    and next technical action.
                                    """)
                            .toolProfile("project-management"))
                    .to("direct:act")
                    .process(routine.act("status-review")
                            .prompt("""
                                    Check the current status field for one assigned task
                                    in the project management system. Report whether it
                                    matches the observed delivery state.
                                    """)
                            .toolProfile("project-management"))
                    .to("direct:act")
                    .process(routine.act("service-context-check")
                            .prompt("""
                                    Check implementation context in service catalog for
                                    the current task. If no service catalog tool profile
                                    is configured, report that capability gap plainly.
                                    """))
                    .to("direct:act")
                    .process(routine.act("wiki-engineering-context-read")
                            .prompt("""
                                    Read the most relevant current engineering note in
                                    the shared markdown wiki repository. Report missing
                                    repository or note blockers plainly.
                                    """)
                            .toolProfile("source-control"))
                    .to("direct:act")
                    .process(routine.act("implementation-intake")
                            .prompt("""
                                    Define one small implementation action for the current
                                    task. Include expected files, tests, and prerequisite
                                    repository or project management state.
                                    """))
                    .to("direct:act")
                    .process(routine.act("source-repository-readiness")
                            .prompt("""
                                    Confirm the target application source repository from
                                    project basics. If named, create or confirm it in
                                    source control. If missing, report the blocker.
                                    """)
                            .toolProfile("source-control"))
                    .to("direct:act")
                    .process(routine.act("delivery-start")
                            .prompt("""
                                    Start one small delivery action for the current task.
                                    Prefer a test-first step when appropriate and report
                                    exact artifacts touched or why delivery cannot start.
                                    """)
                            .toolProfile("source-control"))
                    .to("direct:act")
                    .process(routine.act("change-review-readiness")
                            .prompt("""
                                    Check one change-review readiness condition for the
                                    current task, including branch state, tests,
                                    reviewability, or missing implementation evidence.
                                    """)
                            .toolProfile("source-control"))
                    .to("direct:act")
                    .process(routine.act("work-item-update")
                            .prompt("""
                                    Update one project management field for the current
                                    task if needed. Only change state supported by the
                                    observed delivery evidence.
                                    """)
                            .toolProfile("project-management"))
                    .to("direct:act")
                    .process(routine.act("work-item-note-update")
                            .prompt("""
                                    Write one small delivery note in the project
                                    management system if needed, including blocker,
                                    artifact, change review, or next step details.
                                    """)
                            .toolProfile("project-management"))
                    .to("direct:act")
                    .process(routine.act("delivery-wrap-up")
                            .prompt("""
                                    Summarize one current blocker or one confirmed
                                    delivery outcome for this routine pass. Include
                                    whether Director or PM attention is needed.
                                    """))
                    .to("direct:act")
                    .process(routine.act("wiki-closeout")
                            .prompt("""
                                    Write one small engineering note in the shared
                                    markdown wiki repository if needed. Keep it factual
                                    and tied to verified delivery state.
                                    """)
                            .toolProfile("source-control"))
                    .to("direct:act")
                    .process(routine.decide("application-developer-done")
                            .prompt("""
                                    Is the application developer routine complete for this
                                    pass, with either a concrete delivery outcome or a
                                    clearly reported blocker?
                                    """))
                    .to("direct:decide")
                    .process(routine.exitThought("application-developer-cycle"))
                .end()
                .process(routine.exitThought("application-developer-routine"));
    }
}
