package us.nusun.llmshare.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.framework.inventory.RoutineProvider;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.routine.RoutineSupport;

import java.util.List;

import us.nusun.llmshare.example.tool.ProjectManagementToolProfile;
import us.nusun.llmshare.example.tool.SourceControlToolProfile;
import us.nusun.llmshare.example.tool.TeamChatToolProfile;
import us.nusun.llmshare.example.tool.WorkspaceReadonlyToolProfile;

@ApplicationScoped
public class ApplicationDeveloperRoutine extends RouteBuilder implements RoutineProvider {
    public static final String ID = "software-team/application-developer";
    public static final String APPLICATION_DEVELOPER_ROUTINE_THOUGHT = "Durable developer anchors: documentation index, engineering documentation index, source repository URL, Plane project, team chat, current-work page, persistent repository/tool blockers.";
    public static final String DEV_BASICS_THOUGHT = "Current project-basics read: source repository, documentation repository, Plane project, team chat, current work, blockers, engineering navigation. Promote durable anchors to application-developer-routine before basics exits.";
    public static final String DEV_CYCLE_THOUGHT = "Current developer pass: selected assigned task, Plane work item id/link, canonical documentation page, branch/commit/pull request, validation state, blocker, next code step.";
    public static final String DEV_SELECTION_THOUGHT = "Task selection only: selected assigned in-progress/to-do task, status, owner, priority, canonical page, repository, next code step. Promote selected task to dev-cycle before selection exits.";
    public static final String DEV_IMPLEMENTATION_THOUGHT = "Current implementation step: files inspected/changed, branch, commit/pull request, validation evidence, blocker, next patch. Promote branch/commit/validation/blocker to dev-cycle before implementation exits.";

    @Inject RoutineSupport routine;

    @Override
    public List<RoutineDefinition> routines() {
        return List.of(routine());
    }

    public RoutineDefinition routine() {
        return RoutineDefinition.builder(ID)
                .name("Application Developer")
                .description("""
                        Drive implementation through documentation-backed assignment selection,
                        incremental source patches, explicit status movement, review evidence, and closeout.
                        """)
                .routeUri("direct:example-software-team-application-developer")
                .defaultLane("high-pass")
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
                    .process(routine.act("dev-workspace-basics-read")
                            .prompt("""
                                    Verify project basics across the workspace without mutation.
                                    Use Gitea for the documentation index and direct project-basics/current-work/engineering links,
                                    Plane for project and work item state, and Mattermost for recent setup conversation.
                                    Capture bootstrap status, Git organization agent-workspace, documentation repository URL,
                                    Plane project, team chat, source repository, current work, blockers, and engineering page map.
                                    Do not scan whole projects, repositories, documentation repositories, or channels.
                                    """)
                            .toolProfile(WorkspaceReadonlyToolProfile.ID))
                    .to("direct:act")
                    .process(routine.decide("developer-has-project-basics")
                            .prompt("""
                                    Are project basics present and linked enough to do development: bootstrap status,
                                    Git organization agent-workspace, Plane project, source repository under agent-workspace,
                                    team chat channel, current work, blockers, and dev navigation?
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
                                Read the last 20 messages in the default or project Mattermost channel.
                                If PM/setup discussion exists, respond there only when a specific technical/setup
                                answer is useful. Otherwise post one blocker asking PM to finish project basics
                                only if no current blocker exists. Include the documentation index link if known.
                                Do not start development work; keep rechecking project basics in the next loop.
                                """)
                        .toolProfile(TeamChatToolProfile.ID))
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
                                Read the last 20 project Mattermost messages from the project-basics link. Reply first to a direct
                                technical question, blocker, review request, or handoff. If blocked, ask at most one
                                named question. Do not volunteer for unassigned work or repeat Plane/documentation data.
                                """)
                        .toolProfile(TeamChatToolProfile.ID))
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
                                    In the Plane project from project basics, review only in-progress tasks assigned to this developer.
                                    Choose the highest-priority task with a source-control next step.
                                    Do not take new to-do work while assigned in-progress work can still move.
                                    """)
                            .toolProfile(ProjectManagementToolProfile.ID))
                    .to("direct:act")
                    .process(routine.decide("developer-has-work")
                            .prompt("""
                                    Is an in-progress task assigned to this developer selected for implementation now?
                                    """))
                    .to("direct:decide")
                    .choice()
                        .when(exchangeProperty("decision.developer-has-work.result").isNotEqualTo(true))
                            .process(routine.act("dev-assigned-to-do-start")
                                    .prompt("""
                                            Review one task assigned to this developer in to-do status only.
                                            Ignore unassigned tasks, backlog/product items, and tasks assigned to others.
                                            Select only actionable implementation work with canonical documentation page,
                                            acceptance criteria, source repository under agent-workspace, priority,
                                            and next code step. If the selected task is assigned to this developer,
                                            currently to-do, and has a canonical documentation page, move exactly that task
                                            to in-progress. Otherwise do not move it; add a blocker note instead.
                                            """)
                                    .toolProfile(ProjectManagementToolProfile.ID))
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
                                    Read the selected Plane task only. Verify current status, objective, blocker,
                                    assignee is this developer, canonical documentation page, acceptance criteria,
                                    source repository under agent-workspace, and expected review evidence.
                                    """)
                            .toolProfile(ProjectManagementToolProfile.ID))
                    .to("direct:act")
                    .process(routine.act("dev-source-context-plan")
                            .prompt("""
                                    Read only the documentation index, current-work, canonical item page, direct engineering links,
                                    and source files relevant to the selected task. Verify the source repository is owned by Git
                                    organization agent-workspace; do not create or use repositories in personal namespaces.
                                    Report missing navigation, repository access, or branch blockers. Decide the next coherent patch:
                                    production files, tests, docs/config if needed, acceptance target, branch plan, and validation evidence.
                                    If the repository is greenfield, plan the smallest runnable skeleton needed for the task.
                                    Do not modify source files in this planning step.
                                    """)
                            .toolProfile(SourceControlToolProfile.ID))
                    .to("direct:act")
                    .process(routine.act("dev-delivery-step")
                            .prompt("""
                                    Apply the next coherent source-control patch for the selected task.
                                    Prefer production code plus a matching test/update over documentation or Mattermost notes.
                                    Use or create a task branch and commit if supported by the source-control tools.
                                    If no code change is possible, record the exact blocker and owner.
                                    """)
                            .toolProfile(SourceControlToolProfile.ID))
                    .to("direct:act")
                    .process(routine.act("dev-validation-step")
                            .prompt("""
                                    Check available validation evidence for the patch: tests, CI/status,
                                    changed-file review, or why validation cannot run with the current tools.
                                    Do not claim tests passed unless the tool result shows it.
                                    """)
                            .toolProfile(SourceControlToolProfile.ID))
                    .to("direct:act")
                    .process(routine.act("dev-review-readiness")
                            .prompt("""
                                    Check review readiness in source control: changed files, branch, commit,
                                    Gitea pull request link if supported, and validation status if available.
                                    No review-ready status without a code diff or explicit source-control blocker.
                                    """)
                            .toolProfile(SourceControlToolProfile.ID))
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
                .process(routine.act("dev-plane-status-evidence-update")
                        .prompt("""
                                Verify current status and assignee before changing the selected Plane task.
                                Move to-do -> in-progress only for a selected task assigned to this developer.
                                Move in-progress -> review only with branch, commit or diff plus validation evidence.
                                Move to done only if the workflow permits it and acceptance evidence is recorded.
                                Otherwise keep in-progress and add the next patch or blocker. Add one compact Plane note
                                with source-control evidence, branch/commit/Gitea pull request or diff, validation status,
                                blocker, next step, canonical documentation page, and engineering note link.
                                Keep status and evidence consistent.
                                """)
                        .toolProfile(ProjectManagementToolProfile.ID))
                .to("direct:act");

        from("direct:dev-work-closeout")
                .routeId("dev-work-closeout")
                .process(routine.act("dev-documentation-closeout")
                        .prompt("""
                                Update one focused engineering note if needed: changed files, code outcome,
                                tests or validation gaps, review evidence, and next step.
                                Link source-control evidence from the canonical item page and engineering index.
                                The documentation summarizes durable code knowledge; it does not replace source changes.
                                """)
                        .toolProfile(SourceControlToolProfile.ID))
                .to("direct:act")
                .process(routine.act("dev-team-chat-closeout")
                        .prompt("""
                                Read the last 20 messages in the project Mattermost channel from project basics.
                                Post only if delivery evidence changed, a blocker needs a named owner, or a teammate is waiting.
                                Include Plane work item and canonical documentation links; otherwise stay quiet.
                                """)
                        .toolProfile(TeamChatToolProfile.ID))
                .to("direct:act");

        from("direct:dev-no-work-closeout")
                .routeId("dev-no-work-closeout")
                .process(routine.act("dev-no-work-note")
                        .prompt("""
                                Read the last 20 messages in the project Mattermost channel from project basics.
                                If no in-progress or assigned ready to-do work exists, reply to an existing PM thread if present.
                                Otherwise ask PM once for one assigned actionable to-do. Include the documentation index link.
                                """)
                        .toolProfile(TeamChatToolProfile.ID))
                .to("direct:act");
    }
}
