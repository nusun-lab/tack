package us.nusun.llmshare.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.framework.inventory.RoutineProvider;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.routine.RoutineSupport;

import java.util.List;


@ApplicationScoped
public class ProductOwnerRoutine extends RouteBuilder implements RoutineProvider {
    public static final String ID = "software-team/product-owner";

    @Inject RoutineSupport routine;

    @Override
    public List<RoutineDefinition> routines() {
        return List.of(routine());
    }

    public RoutineDefinition routine() {
        return RoutineDefinition.builder(ID)
                .name("Product Owner")
                .description("""
                        Clarify product focus through chat sync, backlog review,
                        acceptance criteria, priority updates, decisions, and closeout.
                        """)
                .routeUri("direct:example-software-team-product-owner")
                .defaultToolRuntime("tool-low")
                .defaultSynthesisRuntime("synthesis-low")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-software-team-product-owner")
                .routeId("example-software-team-product-owner")
                .process(routine.enterThought("product-owner-routine", """
                        Product Owner working context: product goal, users,
                        acceptance criteria, scope boundaries, project management
                        work breakdown, open questions, and PM dependencies.
                        """, 900))
                .setProperty("productOwnerCycle", constant(0))
                .loopDoWhile(simple("""
                        ${exchangeProperty[decision.product-owner-done.result]} != true
                        && ${exchangeProperty.productOwnerCycle} < 3
                        """))
                    .setProperty("productOwnerCycle", simple("${exchangeProperty.productOwnerCycle++}"))
                    .process(routine.enterThought("product-owner-cycle", """
                            Product Owner loop context: current product focus,
                            backlog item, acceptance criteria, product decisions,
                            unresolved questions, and follow-up notes for this cycle.
                            """, 500))
                    .process(routine.act("chat-focus-post")
                            .prompt("""
                                    Post the current product focus in the shared chat
                                    channel. Use verified project basics and avoid
                                    inventing channels or project state.
                                    """)
                            .toolProfile("team-chat"))
                    .to("direct:act")
                    .process(routine.act("collaboration-sync")
                            .prompt("""
                                    Ask one focused product question or give one
                                    focused product clarification in the shared chat
                                    channel.
                                    """)
                            .toolProfile("team-chat"))
                    .to("direct:act")
                    .process(routine.act("backlog-review")
                            .prompt("""
                                    Review the current backlog item or current
                                    priority in the project management system.
                                    Capture scope, priority, owner, and acceptance gaps.
                                    """)
                            .toolProfile("project-management"))
                    .to("direct:act")
                    .process(routine.act("service-context-check")
                            .prompt("""
                                    Check service context in service catalog for the
                                    current product work. If no service catalog tool
                                    profile is configured, report that capability gap.
                                    """))
                    .to("direct:act")
                    .process(routine.act("wiki-product-context-read")
                            .prompt("""
                                    Read the most relevant current product note in the
                                    shared markdown wiki repository. Report missing
                                    source repository or product note blockers plainly.
                                    """)
                            .toolProfile("source-control"))
                    .to("direct:act")
                    .process(routine.act("scope-refinement")
                            .prompt("""
                                    Refine one scope boundary for the current work.
                                    State what is in scope, out of scope, and what
                                    remains unknown.
                                    """))
                    .to("direct:act")
                    .process(routine.act("gherkin-update")
                            .prompt("""
                                    Draft or revise one Gherkin acceptance criterion
                                    for the current work. Keep it testable and tied to
                                    the current backlog item.
                                    """))
                    .to("direct:act")
                    .process(routine.act("priority-update")
                            .prompt("""
                                    Update one priority or backlog field in the project
                                    management system if a change is needed. Only update
                                    state supported by the current product decision.
                                    """)
                            .toolProfile("project-management"))
                    .to("direct:act")
                    .process(routine.act("wiki-decision-update")
                            .prompt("""
                                    Write one small product decision note in the shared
                                    markdown wiki repository. Include context, decision,
                                    consequence, and unresolved follow-up if any.
                                    """)
                            .toolProfile("source-control"))
                    .to("direct:act")
                    .process(routine.act("constraint-reconciliation")
                            .prompt("""
                                    Resolve one open product question against known
                                    service constraints. If constraints are missing,
                                    report the uncertainty explicitly.
                                    """))
                    .to("direct:act")
                    .process(routine.act("loop-closeout")
                            .prompt("""
                                    Summarize one remaining product gap or one confirmed
                                    product outcome for this routine pass. Include whether
                                    PM or Director attention is needed.
                                    """))
                    .to("direct:act")
                    .process(routine.act("wiki-closeout")
                            .prompt("""
                                    Write one small unresolved product follow-up note in
                                    the shared markdown wiki repository if needed. Keep it
                                    factual and tied to the current product state.
                                    """)
                            .toolProfile("source-control"))
                    .to("direct:act")
                    .process(routine.decide("product-owner-done")
                            .prompt("""
                                    Is the Product Owner routine complete for this pass,
                                    with scope, acceptance criteria, priority, and
                                    follow-up state clearly represented?
                                    """))
                    .to("direct:decide")
                    .process(routine.exitThought("product-owner-cycle"))
                .end()
                .process(routine.exitThought("product-owner-routine"));
    }
}
