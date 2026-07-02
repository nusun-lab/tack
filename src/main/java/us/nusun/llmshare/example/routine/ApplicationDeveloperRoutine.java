package us.nusun.llmshare.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.framework.inventory.RoutineProvider;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.routine.RoutineSupport;

import java.util.List;

import static org.apache.camel.builder.PredicateBuilder.and;

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
                        wiki context, delivery, change-review readiness, and closeout notes.
                        """)
                .routeUri("direct:example-software-team-application-developer")
                .defaultToolRuntime("tool-high")
                .defaultSynthesisRuntime("synthesis-low")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-software-team-application-developer")
                .routeId("example-software-team-application-developer")
                .process(routine.enterThought("application-developer-routine", """
                        Developer working context: assigned work, target source
                        repository, wiki engineering notes, recent team chat, tests,
                        implementation decisions, change-review readiness, tool evidence,
                        and technical blockers.
                        """, 1100))
                .setProperty("applicationDeveloperCycle", constant(0))
                .loopDoWhile(and(
                        exchangeProperty("decision.application-developer-done.result").isNotEqualTo(true),
                        exchangeProperty("applicationDeveloperCycle").isLessThan(3)))
                    .setProperty("applicationDeveloperCycle", simple("${exchangeProperty.applicationDeveloperCycle++}"))
                    .process(routine.enterThought("application-developer-cycle", """
                            Developer loop context: current assigned task, source
                            repository readiness, wiki engineering context,
                            implementation/test action, change-review readiness,
                            project management updates, recent team chat, and closeout
                            evidence for this cycle.
                            """, 600))
                    .process(routine.act("chat-kickoff")
                            .prompt("""
                                    Read recent messages in the shared chat channel for
                                    PM setup notes, product clarifications, and developer
                                    blockers. Post the current implementation focus only
                                    if it is missing or stale. Use verified project basics
                                    and do not invent channels.
                                    """)
                            .toolProfile("team-chat"))
                    .to("direct:act")
                    .process(routine.act("collaboration-sync")
                            .prompt("""
                                    Read the latest shared chat context, then ask one
                                    focused implementation question or give one focused
                                    implementation status update only if it unblocks work.
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
                                    Read current service, architecture, setup, or repository
                                    context from the shared markdown wiki repository and
                                    source repository notes. If context is missing, report
                                    the specific missing note or repository blocker plainly.
                                    """)
                            .toolProfile("source-control"))
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
                                    Update or annotate the assigned project-management task
                                    with one small implementation action. Include expected
                                    files, tests, and prerequisite repository or wiki state
                                    needed before changing code.
                                    """)
                            .toolProfile("project-management"))
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
                                    Read recent team chat before posting. If there is a
                                    current blocker or confirmed delivery outcome that
                                    affects PM or product work, post one concise status
                                    message and whether Director or PM attention is needed.
                                    """)
                            .toolProfile("team-chat"))
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
