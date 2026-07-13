package us.nusun.llmshare.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.framework.inventory.RoutineProvider;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.tack.TackHeaders;

import java.util.List;

import us.nusun.llmshare.example.tool.ProjectManagementToolProfile;
import us.nusun.llmshare.example.tool.SourceControlToolProfile;
import us.nusun.llmshare.example.tool.TeamChatToolProfile;
import us.nusun.llmshare.example.tool.WorkspaceReadonlyToolProfile;

@ApplicationScoped
public class ApplicationDeveloperRoutine extends RouteBuilder implements RoutineProvider {
    public static final String ID = "software-team/application-developer";

    @Override
    public List<RoutineDefinition> routines() {
        return List.of(routine());
    }

    public RoutineDefinition routine() {
        return RoutineDefinition.builder(ID)
                .name("Application Developer")
                .main("""
                        Full Application Developer routine: self-directed implementation cycle. Gather project context,
                        select assigned actionable work, make incremental source changes, collect validation evidence,
                        update task status, communicate delivery evidence, and ask PM when no ready assigned work exists.
                        """, "direct:example-software-team-application-developer")
                .subroutine(
                        "work-selection",
                        "Find or start one assigned actionable implementation task without changing source files.",
                        "direct:example-software-team-application-developer-work-selection")
                .subroutine(
                        "implementation",
                        "Advance one assigned implementation task through code, validation, and status evidence.",
                        "direct:example-software-team-application-developer-implementation")
                .defaultLane("high-pass")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-software-team-application-developer")
                .routeId("example-software-team-application-developer")
                .to("direct:dev-application-developer-root-enter")
                .to("direct:dev-project-basics-loop")
                .to("direct:dev-delivery-loop")
                .to("direct:dev-application-developer-root-exit");

        from("direct:example-software-team-application-developer-work-selection")
                .routeId("example-software-team-application-developer-work-selection")
                .to("direct:dev-application-developer-root-enter")
                .to("direct:dev-project-basics-loop")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Focused developer selection pass: assigned in-progress or ready to-do task, owner, status, canonical page, repository, priority, and next code step.
                        """))
                .to("tack:thought-enter?name=dev-cycle&maxTokens=300")
                .setProperty("decision.developer-has-work.result", constant(false))
                .to("direct:dev-team-chat-sync")
                .to("direct:dev-work-selection-loop")
                .choice()
                    .when(exchangeProperty("decision.developer-has-work.result").isNotEqualTo(true))
                        .to("direct:dev-no-work-closeout")
                .end()
                .to("tack:thought-exit?name=dev-cycle")
                .to("direct:dev-application-developer-root-exit");

        from("direct:example-software-team-application-developer-implementation")
                .routeId("example-software-team-application-developer-implementation")
                .to("direct:dev-application-developer-root-enter")
                .to("direct:dev-project-basics-loop")
                .to("direct:dev-delivery-loop")
                .to("direct:dev-application-developer-root-exit");

        from("direct:dev-application-developer-root-enter")
                .routeId("dev-application-developer-root-enter")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Durable developer anchors: documentation index, engineering documentation index, source repository URL,
                        Plane project, team chat, current-work page.
                        """))
                .to("tack:thought-enter?name=application-developer-routine&maxTokens=500");

        from("direct:dev-application-developer-root-exit")
                .routeId("dev-application-developer-root-exit")
                .to("tack:thought-exit?name=application-developer-routine");

        from("direct:dev-project-basics-loop")
                .routeId("dev-project-basics-loop")
                .setProperty("developerProjectBasicsCycle", constant(0))
                .setProperty("decision.developer-has-project-basics.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.developer-has-project-basics.result").isNotEqualTo(true))
                    .setProperty("developerProjectBasicsCycle", simple("${exchangeProperty.developerProjectBasicsCycle++}"))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Current project-basics read: source repository, documentation repository, Plane project, team chat,
                        current work, engineering navigation. Promote durable anchors to application-developer-routine before basics exits.
                        """))
                    .to("tack:thought-enter?name=dev-basics&maxTokens=300")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Verify developer project basics without mutation.
                                    Check only direct anchors from active thoughts, documentation index/project-basics/current-work,
                                    engineering links, and the minimal Plane/Mattermost lookup needed. Capture source repository,
                                    Plane project, team chat, and engineering navigation.
                                    Do not scan whole projects, repositories, documentation repositories, or channels.
                                    """))
                    .to("tack:act?id=dev-workspace-basics-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Are project basics present and linked enough to do development: bootstrap status,
                                    Git organization agent-workspace, Plane project, source repository under agent-workspace,
                                    team chat channel, current work, and dev navigation?
                                    """))
                    .to("tack:decide?id=developer-has-project-basics")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Read the last 20 messages in the default or project Mattermost channel.
                                    If PM/setup discussion exists, respond there only when a specific technical/setup
                                    answer is useful. Include the documentation index link if known.
                                    Do not start development work; keep rechecking project basics in the next loop.
                                    """))
                    .to("tack:act?id=dev-project-basics-communication&toolProfile=" + TeamChatToolProfile.ID)
                    .to("tack:thought-exit?name=dev-basics")
                .end();



        from("direct:dev-delivery-loop")
                .routeId("dev-delivery-loop")
                .setProperty("applicationDeveloperCycle", constant(0))
                .setProperty("decision.application-developer-done.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.application-developer-done.result").isNotEqualTo(true))
                    .setProperty("applicationDeveloperCycle", simple("${exchangeProperty.applicationDeveloperCycle++}"))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Current developer pass: selected assigned task, Plane work item id/link, canonical documentation page,
                        branch/commit/pull request, validation state.
                        """))
                    .to("tack:thought-enter?name=dev-cycle&maxTokens=300")
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
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is this DEV pass complete because one assigned task reached review/done/blocked
                                    with evidence, or because no eligible assigned work exists and PM was asked?
                                    Do not finish merely because one small code step landed while a clear next
                                    implementation patch remains.
                                    """))
                    .to("tack:decide?id=application-developer-done")
                    .to("tack:thought-exit?name=dev-cycle")
                .end();

        from("direct:dev-team-chat-sync")
                .routeId("dev-team-chat-sync")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Read the last 20 project Mattermost messages from the project-basics link. Reply first to a direct
                                technical question, review request, or handoff. If blocked, ask at most one
                                named question. Do not volunteer for unassigned work or repeat Plane/documentation data.
                                """))
                .to("tack:act?id=dev-team-chat-sync&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:dev-work-selection-loop")
                .routeId("dev-work-selection-loop")
                .setProperty("developerWorkSelectionCycle", constant(0))
                .setProperty("decision.developer-work-selection-done.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.developer-work-selection-done.result").isNotEqualTo(true))
                    .setProperty("developerWorkSelectionCycle", simple("${exchangeProperty.developerWorkSelectionCycle++}"))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Task selection only: selected assigned in-progress/to-do task, status, owner, priority, canonical page, repository, next code step. Promote selected task to dev-cycle before selection exits.
                        """))
                    .to("tack:thought-enter?name=dev-selection&maxTokens=250")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    In the Plane project from project basics, review only in-progress tasks assigned to this developer.
                                    Choose the highest-priority task with a source-control next step.
                                    Do not take new to-do work while assigned in-progress work can still move.
                                    """))
                    .to("tack:act?id=dev-in-progress-review&toolProfile=" + ProjectManagementToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is an in-progress task assigned to this developer selected for implementation now?
                                    """))
                    .to("tack:decide?id=developer-has-work")
                    .choice()
                        .when(exchangeProperty("decision.developer-has-work.result").isNotEqualTo(true))
                            .setHeader(TackHeaders.PROMPT, constant("""
                                            Review one task assigned to this developer in to-do status only.
                                            Ignore unassigned tasks, backlog/product items, and tasks assigned to others.
                                            Select only actionable implementation work with canonical documentation page,
                                            acceptance criteria, source repository under agent-workspace, priority,
                                            and next code step. If the selected task is assigned to this developer,
                                            currently to-do, and has a canonical documentation page, move exactly that task
                                            to in-progress.
                                            """))
                            .to("tack:act?id=dev-assigned-to-do-start&toolProfile=" + ProjectManagementToolProfile.ID)
                            .setHeader(TackHeaders.PROMPT, constant("""
                                            Is there now a selected task assigned to this developer that was already
                                            in-progress or moved from to-do to in-progress?
                                            """))
                            .to("tack:decide?id=developer-has-work")
                    .end()
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Stop selection if assigned active work is selected or no assigned ready to-do remains.
                                    Do not browse or claim backlog work.
                                    """))
                    .to("tack:decide?id=developer-work-selection-done")
                    .to("tack:thought-exit?name=dev-selection")
                .end();

        from("direct:dev-implementation-loop")
                .routeId("dev-implementation-loop")
                .setProperty("developerImplementationCycle", constant(0))
                .setProperty("decision.developer-implementation-complete.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.developer-implementation-complete.result").isNotEqualTo(true))
                    .setProperty("developerImplementationCycle", simple("${exchangeProperty.developerImplementationCycle++}"))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Current implementation step: files inspected/changed, branch, commit/pull request, validation evidence.
                        Promote branch/commit/validation to dev-cycle before implementation exits.
                        """))
                    .to("tack:thought-enter?name=dev-implementation&maxTokens=250")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Read the selected Plane task only. Verify current status, objective,
                                    assignee is this developer, canonical documentation page, acceptance criteria,
                                    source repository under agent-workspace, and expected review evidence.
                                    """))
                    .to("tack:act?id=dev-task-context-read&toolProfile=" + ProjectManagementToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Read only direct documentation links and source files needed for the selected task.
                                    Verify the repository is under agent-workspace. Do not use personal namespaces.
                                    Identify one next coherent patch with files, test/validation target, and branch plan.
                                    Do not modify source files in this planning step.
                                    """))
                    .to("tack:act?id=dev-source-context-plan&toolProfile=" + SourceControlToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Apply exactly one coherent source-control patch for the selected task.
                                    Prefer production code plus a matching test/update. Use or create a task branch and commit if supported.
                                    """))
                    .to("tack:act?id=dev-delivery-step&toolProfile=" + SourceControlToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Check available validation evidence for the patch: tests, CI/status,
                                    changed-file review, or why validation cannot run with the current tools.
                                    Do not claim tests passed unless the tool result shows it.
                                    """))
                    .to("tack:act?id=dev-validation-step&toolProfile=" + SourceControlToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Check review readiness in source control: changed files, branch, commit,
                                    Gitea pull request link if supported, and validation status if available.
                                    No review-ready status without a code diff.
                                    """))
                    .to("tack:act?id=dev-review-readiness&toolProfile=" + SourceControlToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Update exactly one selected Plane task after verifying current status and assignee.
                                    Move to-do -> in-progress only for this developer's selected task. Move in-progress -> review only
                                    with branch, commit or diff plus validation evidence. Otherwise keep status and add one compact note
                                    with source evidence, validation, and canonical documentation link.
                                    """))
                    .to("tack:act?id=dev-plane-status-evidence-update&toolProfile=" + ProjectManagementToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is the selected task review-ready/done with code diff, branch or commit,
                                    and validation evidence, or blocked with exact missing owner?
                                    Return false if there is an obvious next production-code or test patch to make.
                                    """))
                    .to("tack:decide?id=developer-implementation-complete")
                    .to("tack:thought-exit?name=dev-implementation")
                .end();

        from("direct:dev-work-closeout")
                .routeId("dev-work-closeout")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Update one focused engineering note if needed: changed files, code outcome,
                                tests or validation gaps, review evidence, and next step.
                                Link source-control evidence from the canonical item page and engineering index.
                                The documentation summarizes durable code knowledge; it does not replace source changes.
                                """))
                .to("tack:act?id=dev-documentation-closeout&toolProfile=" + SourceControlToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                                Read the last 20 messages in the project Mattermost channel from project basics.
                                Post only if delivery evidence changed.
                                Include Plane work item and canonical documentation links; otherwise stay quiet.
                                """))
                .to("tack:act?id=dev-team-chat-closeout&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:dev-no-work-closeout")
                .routeId("dev-no-work-closeout")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Read the last 20 messages in the project Mattermost channel from project basics.
                                If no in-progress or assigned ready to-do work exists, reply to an existing PM thread if present.
                                Otherwise ask PM once for one assigned actionable to-do. Include the documentation index link.
                                """))
                .to("tack:act?id=dev-no-work-note&toolProfile=" + TeamChatToolProfile.ID);
    }
}
