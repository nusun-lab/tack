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
import us.nusun.llmshare.example.tool.WorkspaceCoordinationToolProfile;
import us.nusun.llmshare.example.tool.WorkspaceReadonlyToolProfile;

@ApplicationScoped
public class ProjectManagerRoutine extends RouteBuilder implements RoutineProvider {
    public static final String ID = "software-team/project-manager";

    @Override
    public List<RoutineDefinition> routines() {
        return List.of(routine());
    }

    public RoutineDefinition routine() {
        return RoutineDefinition.builder(ID)
                .name("Project Manager")
                .main("""
                        Establish and maintain the delivery workspace and turn delivery-ready product work into executable
                        team work. Produce small, assigned implementation tasks with clear scope, dependencies, acceptance
                        references, priority, and expected completion evidence. Coordinate active delivery, blockers, review,
                        team access, and durable project status.
                        """, "direct:example-software-team-project-manager")
                .subroutine(
                        "bootstrap",
                        "Provision and verify the shared project-management, documentation, source-control, and team-chat foundation required for delivery.",
                        "direct:example-software-team-project-manager-bootstrap")
                .subroutine(
                        "coordination",
                        "Maintain active delivery by consuming delivery-ready product work, coordinating executable tasks, blockers, review, and durable status.",
                        "direct:example-software-team-project-manager-coordination")
                .subroutine(
                        "task-planning",
                        "Turn one delivery-ready product item into one small assigned implementation task with explicit execution and review evidence.",
                        "direct:example-software-team-project-manager-task-planning")
                .defaultLane("high-pass")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-software-team-project-manager")
                .routeId("example-software-team-project-manager")
                .to("direct:pm-project-manager-root-enter")
                .to("direct:pm-project-basics-check")
                .choice()
                    .when(exchangeProperty("decision.collaboration-setup-complete.result").isNotEqualTo(true))
                        .to("direct:pm-project-bootstrap")
                .end()
                .to("direct:pm-coordination-loop")
                .to("direct:pm-project-manager-root-exit");

        from("direct:example-software-team-project-manager-bootstrap")
                .routeId("example-software-team-project-manager-bootstrap")
                .to("direct:pm-project-manager-root-enter")
                .to("direct:pm-project-basics-check")
                .choice()
                    .when(exchangeProperty("decision.collaboration-setup-complete.result").isNotEqualTo(true))
                        .to("direct:pm-project-bootstrap")
                .end()
                .choice()
                    .when(exchangeProperty("decision.collaboration-setup-complete.result").isEqualTo(true))
                        .to("direct:pm-team-onboarding")
                .end()
                .to("direct:pm-project-manager-root-exit");

        from("direct:example-software-team-project-manager-coordination")
                .routeId("example-software-team-project-manager-coordination")
                .to("direct:pm-project-manager-root-enter")
                .to("direct:pm-project-basics-check")
                .choice()
                    .when(exchangeProperty("decision.collaboration-setup-complete.result").isEqualTo(true))
                        .to("direct:pm-coordination-loop")
                .end()
                .to("direct:pm-project-manager-root-exit");

        from("direct:example-software-team-project-manager-task-planning")
                .routeId("example-software-team-project-manager-task-planning")
                .to("direct:pm-project-manager-root-enter")
                .to("direct:pm-project-basics-check")
                .choice()
                    .when(exchangeProperty("decision.collaboration-setup-complete.result").isEqualTo(true))
                        .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                            Focused delivery-planning pass: one delivery-ready product item, one implementation task,
                            owner, priority, repository, canonical page, risk, and next code step.
                            """))
                        .to("tack:thought-enter?name=pm-cycle&maxTokens=300")
                        .to("direct:pm-documentation-status-sync")
                        .to("direct:pm-team-onboarding")
                        .to("direct:pm-work-intake-loop")
                        .choice()
                            .when(exchangeProperty("decision.pm-has-ready-product-handoff.result").isEqualTo(true))
                                .to("direct:pm-task-planning-loop")
                        .end()
                        .to("direct:pm-team-sync")
                        .to("direct:pm-cycle-closeout")
                        .to("tack:thought-exit?name=pm-cycle")
                .end()
                .to("direct:pm-project-manager-root-exit");

        from("direct:pm-project-manager-root-enter")
                .routeId("pm-project-manager-root-enter")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Durable delivery anchors: documentation index/path, project-basics/current-work pages,
                        team chat, project-management workspace, and source repository.
                        """))
                .to("tack:thought-enter?name=project-manager-routine&maxTokens=500");

        from("direct:pm-project-manager-root-exit")
                .routeId("pm-project-manager-root-exit")
                .to("tack:thought-exit?name=project-manager-routine");

        from("direct:pm-project-basics-check")
                .routeId("pm-project-basics-check")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Verify project basics without mutation using the capabilities available to you.
                                Check only direct anchors from active thoughts, documentation index/project-basics links,
                                and the minimal project-management or team-chat lookup needed to confirm missing basics.
                                Capture bootstrap status, team chat, project-management workspace, source repository under
                                agent-workspace, documentation repository, and documentation navigation. Do not scan whole
                                projects, repositories, documentation repositories, or channels.
                                """))
                .to("tack:act?id=pm-workspace-basics-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                                Are project basics verified and current enough for delivery coordination, with Git organization
                                agent-workspace and working links for team chat, project management, documentation repository,
                                source repository, and documentation navigation?
                                """))
                .to("tack:decide?id=collaboration-setup-complete");

        from("direct:pm-project-bootstrap")
                .routeId("pm-project-bootstrap")
                .setProperty("collaborationSetupAttempt", constant(0))
                .loopDoWhile(exchangeProperty("decision.collaboration-setup-complete.result").isNotEqualTo(true))
                    .setProperty("collaborationSetupAttempt", simple("${exchangeProperty.collaborationSetupAttempt++}"))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Current bootstrap checklist: project-management workspace, team chat, team membership,
                        repositories, and documentation navigation. Promote final anchors before bootstrap exits.
                        """))
                    .to("tack:thought-enter?name=pm-bootstrap&maxTokens=300")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Using the available project-management capability, search direct project names first,
                                    then create or confirm exactly one shared project workspace. Preserve an existing useful
                                    project. Stop after the project workspace is verified.
                                    """))
                    .to("tack:act?id=plane-project-provisioning&toolProfile=" + WorkspaceCoordinationToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Using source control, search direct repository names first, then create or confirm exactly
                                    one markdown documentation repository under Git organization agent-workspace. Never use a
                                    personal namespace and do not create branches, pull requests, tags, releases, issues, labels,
                                    milestones, or time tracking.
                                    """))
                    .to("tack:act?id=documentation-repository-provisioning&toolProfile=" + WorkspaceCoordinationToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Using source control, search direct repository names first, then create or confirm exactly
                                    one target source repository under Git organization agent-workspace. Never use a personal
                                    namespace and do not create branches, pull requests, tags, releases, issues, labels,
                                    milestones, or time tracking.
                                    """))
                    .to("tack:act?id=source-repository-provisioning&toolProfile=" + WorkspaceCoordinationToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Using team chat, create or confirm one private project channel. Invite the current team
                                    and David Gordon (@aphelio). Never create a duplicate channel. Do not post setup status yet.
                                    """))
                    .to("tack:act?id=chat-channel-setup&toolProfile=" + TeamChatToolProfile.ID)
                    .to("direct:pm-team-onboarding")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Create or update the canonical documentation bootstrap pages.
                                    Index: navigation only, linking project-basics.
                                    Project-basics: mission, Git organization agent-workspace, documentation repository URL,
                                    team chat, project-management workspace, source repository, and stable role directory/responsibilities.
                                    Do not document a supposedly current active-run roster; current staffing comes from mission roster context.
                                    Before marking any project basic missing, search active thoughts for a verified current-mission anchor.
                                    """))
                    .to("tack:act?id=documentation-project-index&toolProfile=" + SourceControlToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Read only the documentation index and project-basics page. Verify every project basic has
                                    a working link. Confirm documentation and source repositories are under agent-workspace,
                                    not a personal namespace. Before marking any project basic missing, search active thoughts
                                    for a verified current-mission anchor for that artifact.
                                    """))
                    .to("tack:act?id=documentation-bootstrap-audit&toolProfile=" + SourceControlToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Using team chat, read recent project conversation. If someone asked about setup, answer
                                    that thread. Otherwise post only if basics are missing, stale, or blocked. Keep chat to a
                                    concise pointer; durable project-management and source-control state are authoritative.
                                    Include the project-basics documentation link.
                                    """))
                    .to("tack:act?id=collaboration-setup-message&toolProfile=" + TeamChatToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    After this bootstrap pass, does the documentation index and project-basics page link
                                    Git organization agent-workspace, team chat, project-management workspace, documentation
                                    repository, source repository, and documentation navigation?
                                    """))
                    .to("tack:decide?id=collaboration-setup-complete")
                    .to("tack:thought-exit?name=pm-bootstrap")
                .end();

        from("direct:pm-coordination-loop")
                .routeId("pm-coordination-loop")
                .setProperty("projectManagerCycle", constant(0))
                .setProperty("decision.project-manager-done.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.project-manager-done.result").isNotEqualTo(true))
                    .setProperty("projectManagerCycle", simple("${exchangeProperty.projectManagerCycle++}"))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Current delivery pass: selected product-ready item, selected implementation task,
                        status movement, owners, and risks.
                        """))
                    .to("tack:thought-enter?name=pm-cycle&maxTokens=300")
                    .to("direct:pm-documentation-status-sync")
                    .to("direct:pm-team-onboarding")
                    .to("direct:pm-active-delivery-check")
                    .to("direct:pm-work-intake-loop")
                    .choice()
                        .when(exchangeProperty("decision.pm-has-ready-product-handoff.result").isEqualTo(true))
                            .to("direct:pm-task-planning-loop")
                    .end()
                    .to("direct:pm-team-sync")
                    .to("direct:pm-cycle-closeout")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is this delivery pass locally complete because collaboration basics are established and
                                    one of these occurred: active review/blocker work was coordinated, one PRODUCT READY item
                                    became an assigned actionable implementation task, no PRODUCT READY item exists and that
                                    was clearly determined, or one exact product-input, assignment, or infrastructure blocker
                                    was recorded? Do not wait for downstream work to consume this output.
                                    """))
                    .to("tack:decide?id=project-manager-done")
                    .to("tack:thought-exit?name=pm-cycle")
                .end();

        from("direct:pm-documentation-status-sync")
                .routeId("pm-documentation-status-sync")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Read only current mission status anchors. Use documentation index/current-work links,
                                directly linked project-management items, and recent team-chat handoff context only if needed.
                                Capture owners, work-item links, canonical documentation pages, agent-workspace repository links,
                                or broken navigation. Do not sweep unrelated work.
                                """))
                .to("tack:act?id=pm-workspace-status-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID);

        from("direct:pm-active-delivery-check")
                .routeId("pm-active-delivery-check")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Active delivery coordination: one highest-priority implementation item in Review, Blocked, or In Progress;
                        REVIEW READY evidence, BLOCKED evidence, owner, status decision, and next coordination action.
                        """))
                .to("tack:thought-enter?name=pm-active-delivery&maxTokens=250")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Inspect at most one highest-priority active implementation item, in this order: Review,
                                Blocked, then In Progress. Use minimal direct project-management evidence and any linked
                                source-control evidence. For Review, recognize REVIEW READY and verify branch, commit/pull
                                request or diff, changed files, validation evidence, and acceptance criteria before moving Done.
                                If evidence is insufficient, leave it in Review and record one exact missing review requirement.
                                For Blocked, recognize BLOCKED, identify the required owner, add PRODUCT INPUT REQUIRED only
                                for a product decision, otherwise record the exact coordination action without inventing a solution.
                                Do not create duplicate replacement tasks for active, review, or blocked work.
                                """))
                .to("tack:act?id=pm-active-delivery-check&toolProfile=" + ProjectManagementToolProfile.ID)
                .to("tack:thought-exit?name=pm-active-delivery");

        from("direct:pm-team-onboarding")
                .routeId("pm-team-onboarding")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Keep team access current for the verified project. Compare current mission/team members with
                                project-management workspace members and team-chat channel members. Add every missing teammate
                                where supported; remove nobody unless the mission clearly says they left. Note source-control
                                access gaps, but do not block delivery coordination on repository membership.
                                """))
                .to("tack:act?id=pm-team-onboarding&toolProfile=" + WorkspaceCoordinationToolProfile.ID);

        from("direct:pm-work-intake-loop")
                .routeId("pm-work-intake-loop")
                .setProperty("decision.pm-has-ready-product-handoff.result", constant(false))
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        One delivery-ready product item under review: work-item id/link, priority, canonical acceptance page,
                        PRODUCT READY content, and requested next delivery action. Promote the selected item before intake exits.
                        """))
                .to("tack:thought-enter?name=pm-intake&maxTokens=250")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Determine whether exactly one delivery-ready product item exists now. Select one product item
                                only when it contains a current PRODUCT READY comment. Capture work item, priority, canonical
                                acceptance page, acceptance summary, unresolved product questions, and requested next delivery
                                action. If no ready product item exists, do not invent acceptance criteria and do not enter task planning.
                                """))
                .to("tack:act?id=pm-intake-review&toolProfile=" + ProjectManagementToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                                Does one current product item with PRODUCT READY exist and have enough acceptance context
                                for delivery planning? Return false when no delivery-ready product item exists.
                                """))
                .to("tack:decide?id=pm-has-ready-product-handoff")
                .to("tack:thought-exit?name=pm-intake");

        from("direct:pm-task-planning-loop")
                .routeId("pm-task-planning-loop")
                .setProperty("decision.pm-task-planning-done.result", constant(false))
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        One implementation task being planned: work-item id/link, assignee, repository, canonical acceptance page,
                        acceptance criteria, dependencies, next code step, and expected review evidence.
                        """))
                .to("tack:thought-enter?name=pm-planning&maxTokens=250")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Turn the selected PRODUCT READY slice into one small assigned To Do implementation task.
                                Split oversized project-management content by moving details into smaller child items; keep
                                parents as summaries. Include assignee, priority, source repository, acceptance page,
                                dependencies, next code step, review evidence, and a comment beginning exactly DELIVERY READY.
                                Do not create duplicates or leave ready work unassigned.
                                """))
                .to("tack:act?id=pm-actionable-dev-task&toolProfile=" + ProjectManagementToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                                Is task planning complete because one assigned actionable implementation task is ready with
                                DELIVERY READY, or because one exact missing input or assignment blocker was durably recorded?
                                """))
                .to("tack:decide?id=pm-task-planning-done")
                .to("tack:thought-exit?name=pm-planning");

        from("direct:pm-team-sync")
                .routeId("pm-team-sync")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Read recent project chat and participate when a real coordination exchange helps.
                                Reply to direct questions, review/blocker handoffs, or ask one named question that unblocks work.
                                Avoid generic status; link the project-management item or product documentation when useful.
                                """))
                .to("tack:act?id=pm-team-chat-sync&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:pm-cycle-closeout")
                .routeId("pm-cycle-closeout")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Curate one small product documentation improvement only when it helps navigation or understanding.
                                Keep daily work state in project management, leave ephemeral chat out, and split pages before they
                                grow past a readable size.
                                """))
                .to("tack:act?id=pm-status-note&toolProfile=" + SourceControlToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                                Update one project-management work item only if verified status, risk, review requirement,
                                PRODUCT INPUT REQUIRED, DELIVERY READY, REVIEW READY, or BLOCKED changed. Do not depend on
                                custom states or labels.
                                """))
                .to("tack:act?id=pm-release-risk-update&toolProfile=" + ProjectManagementToolProfile.ID);
    }
}
