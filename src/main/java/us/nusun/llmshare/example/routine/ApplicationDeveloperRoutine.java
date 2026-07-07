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
    public static final String APPLICATION_DEVELOPER_ROUTINE_THOUGHT = "Durable developer anchors: documentation index, engineering documentation index, source repo/clone URL, project tracking workspace, team chat, current-work page, persistent repo/tool blockers.";
    public static final String DEV_BASICS_THOUGHT = "Current project-basics read: source repo, documentation workspace, project tracking workspace, team chat, current work, blockers, engineering navigation. Promote durable anchors to application-developer-routine before basics exits.";
    public static final String DEV_CYCLE_THOUGHT = "Current developer pass: selected assigned task, task id/link, canonical documentation page, branch/commit/change request, validation state, blocker, next code step.";
    public static final String DEV_SELECTION_THOUGHT = "Task selection only: selected assigned in-progress/to-do task, status, owner, priority, canonical page, repo, next code step. Promote selected task to dev-cycle before selection exits.";
    public static final String DEV_IMPLEMENTATION_THOUGHT = "Current implementation step: files inspected/changed, branch, commit/change request, validation evidence, blocker, next patch. Promote branch/commit/validation/blocker to dev-cycle before implementation exits.";

    @Inject RoutineSupport routine;

    @Override
    public List<RoutineDefinition> routines() {
        return List.of(routine());
    }

    public RoutineDefinition routine() {
        return RoutineDefinition.builder(ID)
                .name("Application Developer")
                .description("""
                        Drive implementation through wiki-backed assignment selection,
                        incremental source patches, explicit status movement, review evidence, and closeout.
                        """)
                .routeUri("direct:example-software-team-application-developer")
                .defaultToolRuntime("tool-high")
                .defaultSynthesisRuntime("synthesis-high")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-software-team-application-developer")
                .routeId("example-software-team-application-developer")
                .process(routine.enterThought("application-developer-routine", APPLICATION_DEVELOPER_ROUTINE_THOUGHT, 500))
                .to("direct:dev-project-basics-loop")
                .to("direct:dev-delivery-loop")
                .process(routine.exitThought("application-developer-routine"));

        from("direct:dev-project-basics-loop")
                .routeId("dev-project-basics-loop")
                .setProperty("developerProjectBasicsCycle", constant(0))
                .setProperty("decision.developer-has-project-basics.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.developer-has-project-basics.result").isNotEqualTo(true))
                    .setProperty("developerProjectBasicsCycle", simple("${exchangeProperty.developerProjectBasicsCycle++}"))
                    .process(routine.enterThought("dev-basics", DEV_BASICS_THOUGHT, 300))
                    .process(routine.act("dev-wiki-basics-read")
                            .prompt("""
                                    Open the wiki index only; follow project-basics, current-work, and engineering links if needed.
                                    Capture bootstrap status, Git organization agent-workspace, wiki URL,
                                    PM project, chat, source repo, current work, blockers, and engineering page map.
                                    Do not scan the whole wiki; missing navigation blocks development work.
                                    """)
                            .toolProfile("source-control"))
                    .to("direct:act")
                    .process(routine.decide("developer-has-project-basics")
                            .prompt("""
                                    Are project basics present and linked in the wiki enough to do development:
                                    bootstrap status, Git organization agent-workspace, PM project,
                                    source repo under agent-workspace, chat channel, current work, blockers,
                                    and dev navigation?
                                    """))
                    .to("direct:decide")
                    .choice()
                        .when(exchangeProperty("decision.developer-has-project-basics.result").isNotEqualTo(true))
                            .to("direct:dev-project-basics-blocker")
                    .end()
                    .process(routine.exitThought("dev-basics"))
                .end();

        from("direct:dev-project-basics-blocker")
                .routeId("dev-project-basics-blocker")
                .process(routine.act("dev-project-basics-blocker")
                        .prompt("""
                                Read the last 20 messages in the default or project channel.
                                If PM/setup discussion exists, respond there only when a specific technical/setup
                                answer is useful. Otherwise post one blocker asking PM to finish wiki project basics
                                only if no current blocker exists. Include the wiki link if known.
                                Do not start development work; keep rechecking project basics in the next loop.
                                """)
                        .toolProfile("team-chat"))
                .to("direct:act");

        from("direct:dev-delivery-loop")
                .routeId("dev-delivery-loop")
                .setProperty("applicationDeveloperCycle", constant(0))
                .setProperty("decision.application-developer-done.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.application-developer-done.result").isNotEqualTo(true))
                    .setProperty("applicationDeveloperCycle", simple("${exchangeProperty.applicationDeveloperCycle++}"))
                    .process(routine.enterThought("dev-cycle", DEV_CYCLE_THOUGHT, 300))
                    .setProperty("decision.developer-has-work.result", constant(false))
                    .to("direct:dev-team-chat-sync")
                    .to("direct:dev-work-selection-loop")
                    .choice()
                        .when(exchangeProperty("decision.developer-has-work.result").isEqualTo(true))
                            .to("direct:dev-implementation-loop")
                            .to("direct:dev-work-closeout")
                        .otherwise()
                            .to("direct:dev-no-work-closeout")
                    .end()
                    .process(routine.decide("application-developer-done")
                            .prompt("""
                                    Is this DEV pass complete because one assigned task reached review/done/blocked
                                    with evidence, or because no eligible assigned work exists and PM was asked?
                                    Do not finish merely because one small code step landed while a clear next
                                    implementation patch remains.
                                    """))
                    .to("direct:decide")
                    .process(routine.exitThought("dev-cycle"))
                .end();

        from("direct:dev-team-chat-sync")
                .routeId("dev-team-chat-sync")
                .process(routine.act("dev-team-chat-sync")
                        .prompt("""
                                Read the last 20 project chat messages from the wiki link. Reply first to a direct
                                technical question, blocker, review request, or handoff. If blocked, ask at most one
                                named question. Do not volunteer for unassigned work or repeat PM/wiki data.
                                """)
                        .toolProfile("team-chat"))
                .to("direct:act");

        from("direct:dev-work-selection-loop")
                .routeId("dev-work-selection-loop")
                .setProperty("developerWorkSelectionCycle", constant(0))
                .setProperty("decision.developer-work-selection-done.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.developer-work-selection-done.result").isNotEqualTo(true))
                    .setProperty("developerWorkSelectionCycle", simple("${exchangeProperty.developerWorkSelectionCycle++}"))
                    .process(routine.enterThought("dev-selection", DEV_SELECTION_THOUGHT, 250))
                    .process(routine.act("dev-in-progress-review")
                            .prompt("""
                                    In the PM project from the wiki, review only in-progress tasks assigned to this developer.
                                    Choose the highest-priority task with a source-control next step.
                                    Do not take new to-do work while assigned in-progress work can still move.
                                    """)
                            .toolProfile("project-management"))
                    .to("direct:act")
                    .process(routine.decide("developer-has-work")
                            .prompt("""
                                    Is an in-progress task assigned to this developer selected for implementation now?
                                    """))
                    .to("direct:decide")
                    .choice()
                        .when(exchangeProperty("decision.developer-has-work.result").isNotEqualTo(true))
                            .process(routine.act("dev-to-do-review")
                                    .prompt("""
                                            Review one task assigned to this developer in to-do status only.
                                            Ignore unassigned tasks, backlog/product items, and tasks assigned to others.
                                            Select only actionable implementation work with canonical wiki page,
                                            acceptance criteria, source repo under agent-workspace, priority,
                                            and next code step.
                                            """)
                                    .toolProfile("project-management"))
                            .to("direct:act")
                            .process(routine.act("dev-start-to-do-status")
                                    .prompt("""
                                            If the selected task is assigned to this developer, currently to-do,
                                            and has a canonical wiki page, move exactly that task to in-progress.
                                            Otherwise do not move it; add a blocker note instead.
                                            """)
                                    .toolProfile("project-management"))
                            .to("direct:act")
                            .process(routine.decide("developer-has-work")
                                    .prompt("""
                                            Is there now a selected task assigned to this developer that was already
                                            in-progress or moved from to-do to in-progress?
                                            """))
                            .to("direct:decide")
                    .end()
                    .process(routine.decide("developer-work-selection-done")
                            .prompt("""
                                    Stop selection if assigned active work is selected or no assigned ready to-do remains.
                                    Do not browse or claim backlog work.
                                    """))
                    .to("direct:decide")
                    .process(routine.exitThought("dev-selection"))
                .end();

        from("direct:dev-implementation-loop")
                .routeId("dev-implementation-loop")
                .setProperty("developerImplementationCycle", constant(0))
                .setProperty("decision.developer-implementation-complete.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.developer-implementation-complete.result").isNotEqualTo(true))
                    .setProperty("developerImplementationCycle", simple("${exchangeProperty.developerImplementationCycle++}"))
                    .process(routine.enterThought("dev-implementation", DEV_IMPLEMENTATION_THOUGHT, 250))
                    .process(routine.act("dev-task-context-read")
                            .prompt("""
                                    Read the selected PM task only. Verify current status, objective, blocker,
                                    assignee is this developer, canonical wiki page, acceptance criteria,
                                    source repo under agent-workspace, and expected review evidence.
                                    """)
                            .toolProfile("project-management"))
                    .to("direct:act")
                    .process(routine.act("dev-wiki-engineering-read")
                            .prompt("""
                                    Read only the wiki index, current-work, canonical item page, and direct engineering links.
                                    Do not scan the whole wiki; report missing navigation links.
                                    """)
                            .toolProfile("source-control"))
                    .to("direct:act")
                    .process(routine.act("dev-source-repository-readiness")
                            .prompt("""
                                    Use the source repo from the wiki. Inspect only files relevant to the selected task.
                                    The repo must be owned by Git organization agent-workspace; do not create or use
                                    repositories in personal namespaces.
                                    If repo access is missing, record the blocker. If the task branch is missing,
                                    create or plan it if supported by the source-control tools.
                                    """)
                            .toolProfile("source-control"))
                    .to("direct:act")
                    .process(routine.act("dev-patch-plan")
                            .prompt("""
                                    Inspect only the files needed for the selected task.
                                    Decide the next coherent patch: production files, tests, docs/config if needed,
                                    acceptance target, and validation evidence. If the repo is greenfield,
                                    plan the smallest runnable skeleton needed for the task. Do not touch unrelated files.
                                    """)
                            .toolProfile("source-control"))
                    .to("direct:act")
                    .process(routine.act("dev-delivery-step")
                            .prompt("""
                                    Apply the next coherent source-control patch for the selected task.
                                    Prefer production code plus a matching test/update over wiki or chat notes.
                                    Use or create a task branch and commit if supported by the source-control tools.
                                    If no code change is possible, record the exact blocker and owner.
                                    """)
                            .toolProfile("source-control"))
                    .to("direct:act")
                    .process(routine.act("dev-validation-step")
                            .prompt("""
                                    Check available validation evidence for the patch: tests, CI/status,
                                    changed-file review, or why validation cannot run with the current tools.
                                    Do not claim tests passed unless the tool result shows it.
                                    """)
                            .toolProfile("source-control"))
                    .to("direct:act")
                    .process(routine.act("dev-review-readiness")
                            .prompt("""
                                    Check review readiness in source control: changed files, branch, commit,
                                    PR/review link if supported, and validation status if available.
                                    No review-ready status without a code diff or explicit source-control blocker.
                                    """)
                            .toolProfile("source-control"))
                    .to("direct:act")
                    .to("direct:dev-status-transition")
                    .process(routine.decide("developer-implementation-complete")
                            .prompt("""
                                    Is the selected task review-ready/done with code diff, branch or commit,
                                    and validation evidence, or blocked with exact missing owner?
                                    Return false if there is an obvious next production-code or test patch to make.
                                    """))
                    .to("direct:decide")
                    .process(routine.exitThought("dev-implementation"))
                .end();

        from("direct:dev-status-transition")
                .routeId("dev-status-transition")
                .process(routine.act("dev-status-update")
                        .prompt("""
                                Verify current status and assignee before changing the selected PM task.
                                Move to-do -> in-progress only for a selected task assigned to this developer.
                                Move in-progress -> review only with branch, commit or diff plus validation evidence.
                                Move to done only if the workflow permits it and acceptance evidence is recorded.
                                Otherwise keep in-progress and add the next patch or blocker.
                                """)
                        .toolProfile("project-management"))
                .to("direct:act")
                .process(routine.act("dev-work-item-note")
                        .prompt("""
                                Add one compact PM note with source-control evidence, branch/commit/PR or diff,
                                validation status, blocker, next step, canonical wiki page, and engineering note link.
                                Keep status and evidence consistent.
                                """)
                        .toolProfile("project-management"))
                .to("direct:act");

        from("direct:dev-work-closeout")
                .routeId("dev-work-closeout")
                .process(routine.act("dev-wiki-closeout")
                        .prompt("""
                                Update one focused engineering note if needed: changed files, code outcome,
                                tests or validation gaps, review evidence, and next step.
                                Link source-control evidence from the canonical item page and engineering index.
                                The wiki summarizes durable code knowledge; it does not replace source changes.
                                """)
                        .toolProfile("source-control"))
                .to("direct:act")
                .process(routine.act("dev-team-chat-closeout")
                        .prompt("""
                                Read the last 20 messages in the project chat from the wiki.
                                Post only if delivery evidence changed, a blocker needs a named owner, or a teammate is waiting.
                                Include PM item and canonical wiki links; otherwise stay quiet.
                                """)
                        .toolProfile("team-chat"))
                .to("direct:act");

        from("direct:dev-no-work-closeout")
                .routeId("dev-no-work-closeout")
                .process(routine.act("dev-no-work-note")
                        .prompt("""
                                Read the last 20 messages in the project chat from the wiki.
                                If no in-progress or assigned ready to-do work exists, reply to an existing PM thread if present.
                                Otherwise ask PM once for one assigned actionable to-do. Include the wiki index link.
                                """)
                        .toolProfile("team-chat"))
                .to("direct:act");
    }
}
