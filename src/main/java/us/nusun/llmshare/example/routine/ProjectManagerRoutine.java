package us.nusun.llmshare.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.framework.inventory.RoutineProvider;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.routine.RoutineSupport;

import java.util.List;


@ApplicationScoped
public class ProjectManagerRoutine extends RouteBuilder implements RoutineProvider {
    public static final String ID = "software-team/project-manager";

    @Inject RoutineSupport routine;

    @Override
    public List<RoutineDefinition> routines() {
        return List.of(routine());
    }

    public RoutineDefinition routine() {
        return RoutineDefinition.builder(ID)
                .name("Project Manager")
                .description("""
                        Own collaboration setup, delivery coordination, task breakdown,
                        sequencing, status, risks, and PM closeout.
                        """)
                .routeUri("direct:example-software-team-project-manager")
                .defaultToolRuntime("tool-low")
                .defaultSynthesisRuntime("synthesis-low")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-software-team-project-manager")
                .routeId("example-software-team-project-manager")
                .process(routine.enterThought("project-manager-routine", """
                        Project Manager working context: collaboration basics,
                        delivery risks, project management state, blockers,
                        decisions, David Gordon (@aphelio) coordination,
                        and next actions.
                        """, 900))
                .setProperty("collaborationSetupAttempt", constant(0))
                .process(routine.decide("collaboration-setup-complete")
                        .prompt("""
                                Are the core collaboration basics confirmed: team chat
                                channel, private project management project, shared
                                markdown wiki repository, and target application source
                                repository?
                                """))
                .to("direct:decide")
                .choice()
                    .when(simple("""
                            ${exchangeProperty[decision.collaboration-setup-complete.result]}
                            != true
                            """))
                        .loopDoWhile(simple("""
                                ${exchangeProperty[decision.collaboration-setup-complete.result]}
                                != true
                                && ${exchangeProperty.collaborationSetupAttempt} < 3
                                """))
                            .setProperty("collaborationSetupAttempt", simple("${exchangeProperty.collaborationSetupAttempt++}"))
                            .process(routine.enterThought("collaboration-setup-attempt", """
                                    Collaboration setup context: exact team chat channel,
                                    project management project, wiki repository, source
                                    repository, invitations, and setup blockers for this
                                    attempt.
                                    """, 700))
                            .process(routine.act("forum-fallback-check")
                                    .prompt("""
                                            If the project collaboration location is unknown,
                                            use the default team coordination channel for
                                            coordination. Report the fallback plainly.
                                            """)
                                    .toolProfile("team-chat"))
                            .to("direct:act")
                            .process(routine.act("chat-channel-setup")
                                    .prompt("""
                                            Create or confirm the private team chat project
                                            channel and invite the full team including David
                                            Gordon (@aphelio). Never create a duplicate.
                                            """)
                                    .toolProfile("team-chat"))
                            .to("direct:act")
                            .process(routine.act("project-management-setup")
                                    .prompt("""
                                            Create or confirm the private project management
                                            project and invite the full team including David
                                            Gordon (@aphelio). Never create a duplicate.
                                            """)
                                    .toolProfile("project-management"))
                            .to("direct:act")
                            .process(routine.act("wiki-repo-setup")
                                    .prompt("""
                                            Create or confirm the shared markdown wiki
                                            repository inside the existing source-control
                                            organization shared workspace. Never duplicate.
                                            """)
                                    .toolProfile("source-control"))
                            .to("direct:act")
                            .process(routine.act("source-repository-plan")
                                    .prompt("""
                                            Define or confirm the target application source
                                            repository name and location the developer should
                                            use in source control.
                                            """)
                                    .toolProfile("source-control"))
                            .to("direct:act")
                            .process(routine.act("forum-announcement")
                                    .prompt("""
                                            Post the exact team chat channel, project
                                            management project, wiki repository, and target
                                            application source repository in the default team
                                            coordination channel.
                                            """)
                                    .toolProfile("team-chat"))
                            .to("direct:act")
                            .process(routine.act("collaboration-setup-gap")
                                    .prompt("""
                                            Announce current project setup state in the default
                                            team coordination channel, including missing
                                            invitation, project, repository, or permission gaps.
                                            """)
                                    .toolProfile("team-chat"))
                            .to("direct:act")
                            .process(routine.decide("collaboration-setup-complete")
                                    .prompt("""
                                            After the setup pass, are the core collaboration
                                            basics confirmed: team chat channel, private
                                            project management project, shared markdown wiki
                                            repository, and target source repository?
                                            """))
                            .to("direct:decide")
                            .process(routine.exitThought("collaboration-setup-attempt"))
                        .end()
                .end()
                .setProperty("projectManagerCycle", constant(0))
                .loopDoWhile(simple("""
                        ${exchangeProperty[decision.project-manager-done.result]} != true
                        && ${exchangeProperty.projectManagerCycle} < 3
                        """))
                    .setProperty("projectManagerCycle", simple("${exchangeProperty.projectManagerCycle++}"))
                    .process(routine.enterThought("project-manager-cycle", """
                            Project Manager loop context: current project management
                            item, task breakdown, sequencing, team status,
                            actionability, delivery risk, and closeout for this cycle.
                            """, 500))
                    .process(routine.act("work-item-review")
                            .prompt("""
                                    Review one current PO-driven item in the project
                                    management system. Capture ownership, priority,
                                    blockers, and readiness for task breakdown.
                                    """)
                            .toolProfile("project-management"))
                    .to("direct:act")
                    .process(routine.act("task-breakdown")
                            .prompt("""
                                    Create or define one small discrete project management
                                    task from the current PO-driven item. Keep the task
                                    independently actionable.
                                    """)
                            .toolProfile("project-management"))
                    .to("direct:act")
                    .process(routine.act("task-sequencing")
                            .prompt("""
                                    Set or clarify the sequence of one project management
                                    task, including predecessor, next owner, and immediate
                                    unblock condition.
                                    """)
                            .toolProfile("project-management"))
                    .to("direct:act")
                    .process(routine.act("standup-status-check")
                            .prompt("""
                                    Collect one focused work status update in the shared
                                    team channel. Ask only for the missing status needed
                                    for coordination.
                                    """)
                            .toolProfile("team-chat"))
                    .to("direct:act")
                    .process(routine.act("actionability-check")
                            .prompt("""
                                    Check whether one planned task is actionable now.
                                    Report missing scope, assignment, source repository,
                                    environment, or dependency blockers.
                                    """)
                            .toolProfile("project-management"))
                    .to("direct:act")
                    .process(routine.act("release-checkpoint")
                            .prompt("""
                                    Assess one current delivery or schedule risk. Use
                                    verified project management, team chat, and source
                                    repository state when available. Identify mitigation.
                                    """))
                    .to("direct:act")
                    .process(routine.act("loop-closeout")
                            .prompt("""
                                    Summarize one current blocker or one confirmed
                                    coordination outcome for this routine pass. Include
                                    whether Director attention is needed.
                                    """))
                    .to("direct:act")
                    .process(routine.decide("project-manager-done")
                            .prompt("""
                                    Is the Project Manager routine complete for this pass,
                                    with collaboration/setup state and current delivery
                                    coordination clearly represented?
                                    """))
                    .to("direct:decide")
                    .process(routine.exitThought("project-manager-cycle"))
                .end()
                .process(routine.exitThought("project-manager-routine"));
    }
}
