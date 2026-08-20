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
                        update task status, communicate delivery evidence, and surface the absence of ready assigned work.
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
                .choice()
                    .when(exchangeProperty("decision.developer-has-project-basics.result").isEqualTo(true))
                        .to("direct:dev-delivery-loop")
                    .otherwise()
                        .to("direct:dev-missing-basics-closeout")
                .end()
                .to("direct:dev-application-developer-root-exit");

        from("direct:example-software-team-application-developer-work-selection")
                .routeId("example-software-team-application-developer-work-selection")
                .to("direct:dev-application-developer-root-enter")
                .to("direct:dev-project-basics-loop")
                .choice()
                    .when(exchangeProperty("decision.developer-has-project-basics.result").isEqualTo(true))
                        .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                            Focused developer selection pass: assigned in-progress or ready to-do task, owner, status,
                            canonical page, repository, priority, and next code step.
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
                    .endChoice()
                    .otherwise()
                        .to("direct:dev-missing-basics-closeout")
                .end()
                .to("direct:dev-application-developer-root-exit");

        from("direct:example-software-team-application-developer-implementation")
                .routeId("example-software-team-application-developer-implementation")
                .to("direct:dev-application-developer-root-enter")
                .to("direct:dev-project-basics-loop")
                .choice()
                    .when(exchangeProperty("decision.developer-has-project-basics.result").isEqualTo(true))
                        .to("direct:dev-delivery-loop")
                    .otherwise()
                        .to("direct:dev-missing-basics-closeout")
                .end()
                .to("direct:dev-application-developer-root-exit");

        from("direct:dev-application-developer-root-enter")
                .routeId("dev-application-developer-root-enter")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Durable developer anchors: documentation index, engineering documentation index,
                        source repository URL, project-management workspace, team chat, and current-work page.
                        """))
                .to("tack:thought-enter?name=application-developer-routine&maxTokens=500");

        from("direct:dev-application-developer-root-exit")
                .routeId("dev-application-developer-root-exit")
                .to("tack:thought-exit?name=application-developer-routine");

        from("direct:dev-project-basics-loop")
                .routeId("dev-project-basics-loop")
                .setProperty("decision.developer-has-project-basics.result", constant(false))
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Current project-basics read: source repository, documentation repository,
                        project-management workspace, team chat, current work, and engineering navigation.
                        Promote durable anchors before basics exits.
                        """))
                .to("tack:thought-enter?name=dev-basics&maxTokens=300")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Verify developer project basics once without mutation using the capabilities available to you.
                                Development may begin only when there is a usable project-management workspace, source repository
                                under agent-workspace, documentation repository/current-work navigation, canonical acceptance
                                links, and usable team chat. Do not repeatedly reread project basics and do not start development
                                work when basics are missing.
                                """))
                .to("tack:act?id=dev-workspace-basics-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                                Are project basics present and linked enough to do development: project management,
                                source repository under agent-workspace, documentation/current-work navigation,
                                team chat, and engineering navigation?
                                """))
                .to("tack:decide?id=developer-has-project-basics")
                .to("tack:thought-exit?name=dev-basics");

        from("direct:dev-missing-basics-closeout")
                .routeId("dev-missing-basics-closeout")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Project basics are not usable for development. Read recent team chat only if needed.
                                Reply once to an existing setup thread when a concise technical setup answer helps;
                                otherwise stay quiet. Do not repost setup requests, claim work, or begin development without
                                project management, source repository, documentation repository, and usable navigation.
                                """))
                .to("tack:act?id=dev-missing-basics-closeout&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:dev-delivery-loop")
                .routeId("dev-delivery-loop")
                .setProperty("applicationDeveloperCycle", constant(0))
                .setProperty("decision.application-developer-done.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.application-developer-done.result").isNotEqualTo(true))
                    .setProperty("applicationDeveloperCycle", simple("${exchangeProperty.applicationDeveloperCycle++}"))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Current developer pass: selected assigned task, project-management work-item id/link,
                        canonical documentation page, branch/commit/pull request, and validation state.
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
                                    Is this implementation pass complete because one assigned task reached Review, Done,
                                    or a durable blocked state with evidence, or because no eligible assigned work exists?
                                    Record the absence of eligible assigned work clearly enough for orchestration to respond.
                                    Do not finish merely because one small code step landed while a clear next implementation
                                    patch remains.
                                    """))
                    .to("tack:decide?id=application-developer-done")
                    .to("tack:thought-exit?name=dev-cycle")
                .end();

        from("direct:dev-team-chat-sync")
                .routeId("dev-team-chat-sync")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Read recent project chat and join real technical conversations.
                                Answer direct questions, review requests, or handoffs; if blocked, ask one named question.
                                Do not volunteer for unassigned work or repeat project-management/documentation data.
                                """))
                .to("tack:act?id=dev-team-chat-sync&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:dev-work-selection-loop")
                .routeId("dev-work-selection-loop")
                .setProperty("developerWorkSelectionCycle", constant(0))
                .setProperty("decision.developer-work-selection-done.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.developer-work-selection-done.result").isNotEqualTo(true))
                    .setProperty("developerWorkSelectionCycle", simple("${exchangeProperty.developerWorkSelectionCycle++}"))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Task selection only: selected assigned in-progress/to-do task, status, owner, priority,
                        canonical page, repository, next code step. Promote selected task before selection exits.
                        """))
                    .to("tack:thought-enter?name=dev-selection&maxTokens=250")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Using project management, review only implementation tasks assigned to you.
                                    Continue assigned In Progress work before starting the highest-priority assigned To Do task.
                                    Require source repository, acceptance page, delivery handoff details, and one concrete next
                                    code step. Ignore Backlog, unassigned work, product items, and work assigned to others.
                                    """))
                    .to("tack:act?id=dev-in-progress-review&toolProfile=" + ProjectManagementToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is one of your assigned in-progress tasks selected for implementation now?
                                    """))
                    .to("tack:decide?id=developer-has-work")
                    .choice()
                        .when(exchangeProperty("decision.developer-has-work.result").isNotEqualTo(true))
                            .setHeader(TackHeaders.PROMPT, constant("""
                                            Review one assigned To Do implementation task only. Select it only when it has
                                            acceptance, source repository, priority, dependencies, review evidence, a next code
                                            step, and DELIVERY READY context. If selected, move exactly that task to In Progress.
                                            """))
                            .to("tack:act?id=dev-assigned-to-do-start&toolProfile=" + ProjectManagementToolProfile.ID)
                            .setHeader(TackHeaders.PROMPT, constant("""
                                            Is one of your assigned tasks now selected and in progress?
                                            """))
                            .to("tack:decide?id=developer-has-work")
                    .end()
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Stop selection when assigned active work is selected, or when no eligible assigned
                                    In Progress or To Do implementation task exists. Do not browse or claim backlog/product work.
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
                        Current implementation step: files inspected/changed, branch, commit/pull request,
                        validation evidence. Promote branch/commit/validation before implementation exits.
                        """))
                    .to("tack:thought-enter?name=dev-implementation&maxTokens=250")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Using project management, read the selected implementation task only. Verify current status,
                                    objective, assignee is you, canonical documentation page, acceptance criteria, source repository
                                    under agent-workspace, and expected review evidence.
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
                                    Check available validation evidence for the patch: tests, CI/status, changed-file review,
                                    or why validation cannot run with the current tools. Do not claim tests passed unless the
                                    tool result shows it.
                                    """))
                    .to("tack:act?id=dev-validation-step&toolProfile=" + SourceControlToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Check review readiness in source control: changed files, branch, commit, pull request
                                    if supported, and validation status if available. No review-ready status without a code diff.
                                    """))
                    .to("tack:act?id=dev-review-readiness&toolProfile=" + SourceControlToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Using project management, update exactly one selected task after verifying current status
                                    and assignee. Move To Do -> In Progress only for your selected task. Move In Progress -> Review
                                    only with a real diff plus branch, commit, or pull request and available validation evidence.
                                    When review-ready, create or update a comment beginning exactly REVIEW READY with branch,
                                    commit/pull request or diff, changed files, validation result or limitation, and canonical
                                    acceptance page. When blocked, keep the accurate status and create or update a comment
                                    beginning exactly BLOCKED naming the required owner with evidence. Do not add duplicate
                                    equivalent closeout comments.
                                    """))
                    .to("tack:act?id=dev-plane-status-evidence-update&toolProfile=" + ProjectManagementToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is the selected task Review/Done with a real diff, branch/commit/pull request, and validation
                                    evidence, or durably blocked with BLOCKED naming the required owner? Return false if there
                                    is an obvious next production-code or test patch to make.
                                    """))
                    .to("tack:decide?id=developer-implementation-complete")
                    .to("tack:thought-exit?name=dev-implementation")
                .end();

        from("direct:dev-work-closeout")
                .routeId("dev-work-closeout")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Make one focused documentation improvement if it preserves durable engineering knowledge.
                                Link source evidence from the item page or engineering index; do not create activity logs.
                                Keep files compact and split or summarize when they grow too large.
                                """))
                .to("tack:act?id=dev-documentation-closeout&toolProfile=" + SourceControlToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                                Read recent project chat and reply when delivery evidence, a blocker, or a teammate's question
                                needs interaction. Link the project-management item and review/blocker evidence when useful;
                                avoid generic status.
                                """))
                .to("tack:act?id=dev-team-chat-closeout&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:dev-no-work-closeout")
                .routeId("dev-no-work-closeout")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Read recent project chat. If no assigned In Progress or To Do implementation task exists,
                                state that fact once when useful so the team can respond. Otherwise stay quiet. Do not browse,
                                shape, or claim product backlog work and do not self-assign unassigned implementation work.
                                """))
                .to("tack:act?id=dev-no-work-note&toolProfile=" + TeamChatToolProfile.ID);
    }
}
