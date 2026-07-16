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
                        Full Project Manager routine: self-directed delivery coordination cycle. Verify or create the
                        collaboration foundation when needed, coordinate active work, intake product handoffs, plan developer
                        tasks, synchronize team status, manage risks, and close out durable coordination state.
                        """, "direct:example-software-team-project-manager")
                .subroutine(
                        "bootstrap",
                        "Provision and verify an agile project, documentation repo, and chat channel before delivery work management starts.",
                        "direct:example-software-team-project-manager-bootstrap")
                .subroutine(
                        "coordination",
                        "Coordinate active delivery work after the project foundation is verified.",
                        "direct:example-software-team-project-manager-coordination")
                .subroutine(
                        "task-planning",
                        "Review product handoff and create or update one actionable developer task.",
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
                            Focused PM task-planning pass: one product handoff, one developer task, owner, priority, repository, canonical page, risk, and next code step.
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
                        Durable PM project anchors: documentation index URL/path, project-basics/current-work pages,
                        team chat channel, Plane project, source repository.
                        """))
                .to("tack:thought-enter?name=project-manager-routine&maxTokens=500");

        from("direct:pm-project-manager-root-exit")
                .routeId("pm-project-manager-root-exit")
                .to("tack:thought-exit?name=project-manager-routine");

        from("direct:pm-project-basics-check")
                .routeId("pm-project-basics-check")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Verify project basics without mutation.
                                Check only direct anchors from active thoughts, the documentation index/project-basics links,
                                and the minimal Plane/Mattermost lookup needed to confirm missing basics. Capture bootstrap status,
                                team chat, Plane project, source repository under agent-workspace, documentation repository,
                                and documentation index/project-basics navigation.
                                Do not scan whole projects, repositories, documentation repositories, or channels.
                                """))
                .to("tack:act?id=pm-workspace-basics-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                                Are project basics verified and current enough for delivery coordination, with
                                Git organization agent-workspace and a working link for team chat, Plane project,
                                documentation repository, source repository, and documentation index/project-basics navigation?
                                """))
                .to("tack:decide?id=collaboration-setup-complete");

        from("direct:pm-project-bootstrap")
                .routeId("pm-project-bootstrap")
                .setProperty("collaborationSetupAttempt", constant(0))
                .loopDoWhile(exchangeProperty("decision.collaboration-setup-complete.result").isNotEqualTo(true))
                    .setProperty("collaborationSetupAttempt", simple("${exchangeProperty.collaborationSetupAttempt++}"))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Current bootstrap checklist: Plane project, team chat, team membership, repositories, and documentation navigation.
                        Promote final URLs to project-manager-routine before bootstrap exits.
                        """))
                    .to("tack:thought-enter?name=pm-bootstrap&maxTokens=300")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Search direct project names first, then create or confirm exactly one public Plane project.
                                    Preserve an existing useful project. Stop after the Plane project is verified.
                                    """))
                    .to("tack:act?id=plane-project-provisioning&toolProfile=" + WorkspaceCoordinationToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Search direct repository names first, then create or confirm exactly one markdown documentation
                                    repository under Git organization agent-workspace. Never use a personal namespace and do not
                                    create branches, pull requests, tags, releases, issues, labels, milestones, or time tracking.
                                    """))
                    .to("tack:act?id=documentation-repository-provisioning&toolProfile=" + WorkspaceCoordinationToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Search direct repository names first, then create or confirm exactly one target source repository
                                    under Git organization agent-workspace. Never use a personal namespace and do not create branches,
                                    pull requests, tags, releases, issues, labels, milestones, or time tracking.
                                    """))
                    .to("tack:act?id=source-repository-provisioning&toolProfile=" + WorkspaceCoordinationToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Create or confirm one private project Mattermost channel.
                                    Invite the current team and David Gordon (@aphelio). Never create a duplicate channel.
                                    Do not post setup status yet.
                                    """))
                    .to("tack:act?id=chat-channel-setup&toolProfile=" + TeamChatToolProfile.ID)
                    .to("direct:pm-team-onboarding")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Create or update the canonical documentation bootstrap pages.
                                    Index: navigation only, linking project-basics.
                                    Project-basics: mission, Git organization agent-workspace, documentation repository URL,
                                    team chat, Plane project, source repository, and stable role directory/responsibilities.
                                    Do not document a supposedly current active-run roster; current staffing comes from mission roster context.
                                    Before marking any project basic missing, search active thoughts for a verified
                                    current-mission anchor for that artifact.
                                    """))
                    .to("tack:act?id=documentation-project-index&toolProfile=" + SourceControlToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Read only the documentation index and project-basics page.
                                    Verify every project basic has a working link.
                                    Confirm documentation and source repositories are under agent-workspace, not a personal namespace.
                                    Before marking any project basic missing, search active thoughts for a verified
                                    current-mission anchor for that artifact.
                                    """))
                    .to("tack:act?id=documentation-bootstrap-audit&toolProfile=" + SourceControlToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Read the last 20 messages in the project Mattermost channel, or default channel if absent.
                                    If someone asked about setup, answer that thread. Otherwise post only if basics are missing,
                                    stale, or blocked. Keep Mattermost to a concise pointer; Plane and Gitea are authoritative.
                                    Include the project-basics documentation link.
                                    """))
                    .to("tack:act?id=collaboration-setup-message&toolProfile=" + TeamChatToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    After this bootstrap pass, does the documentation index and project-basics page link
                                    Git organization agent-workspace, team chat, Plane project, documentation repository,
                                    source repository, and documentation index/project-basics navigation?
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
                        Current PM pass: selected product handoff, selected developer task, status movement, owners, risks.
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
                                    Is your PM pass locally complete because collaboration basics are established and one of these occurred:
                                    active review/blocker work was coordinated, one ready PO handoff became an assigned actionable
                                    developer task, no ready PO handoff exists and that was clearly determined, or one exact PO input,
                                    assignment, or infrastructure blocker was recorded? Do not wait for PO or DEV to consume PM output.
                                    """))
                    .to("tack:decide?id=project-manager-done")
                    .to("tack:thought-exit?name=pm-cycle")
                .end();

        from("direct:pm-documentation-status-sync")
                .routeId("pm-documentation-status-sync")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Read only current mission status anchors.
                                Use the documentation index/current-work links, directly linked Plane items, and recent Mattermost
                                handoff context only if needed. Capture owners, Plane links, canonical documentation pages,
                                agent-workspace repository links, or broken navigation. Do not sweep unrelated work.
                                """))
                .to("tack:act?id=pm-workspace-status-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID);

        from("direct:pm-active-delivery-check")
                .routeId("pm-active-delivery-check")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Active delivery coordination: one highest-priority developer item in Review, Blocked, or In Progress;
                        PM REVIEW: READY evidence, DEV BLOCKER: owner, status decision, and next coordination action.
                        """))
                .to("tack:thought-enter?name=pm-active-delivery&maxTokens=250")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Inspect at most one highest-priority active developer item, in this order: Review, Blocked,
                                then In Progress. Use minimal direct Plane evidence and any linked Gitea/source evidence.
                                For Review, recognize PM REVIEW: READY and verify branch, commit/PR or diff, changed files,
                                validation evidence, and acceptance criteria before moving Done. If evidence is insufficient,
                                leave it in Review and record one exact missing review requirement.
                                For Blocked, recognize DEV BLOCKER:, identify the required owner, add PO INPUT REQUIRED: only
                                for a product decision, otherwise record the exact coordination action without inventing a solution.
                                Do not create duplicate replacement tasks for active, review, or blocked work.
                                """))
                .to("tack:act?id=pm-active-delivery-check&toolProfile=" + ProjectManagementToolProfile.ID)
                .to("tack:thought-exit?name=pm-active-delivery");


        from("direct:pm-team-onboarding")
                .routeId("pm-team-onboarding")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Keep team access current for the verified project.
                                Add missing current teammates to the Plane project and Mattermost channel; remove nobody unless the mission clearly says they left.
                                Note Gitea access gaps, but do not block PM work on repository membership.
                                """))
                .to("tack:act?id=pm-team-onboarding&toolProfile=" + WorkspaceCoordinationToolProfile.ID);

        from("direct:pm-work-intake-loop")
                .routeId("pm-work-intake-loop")
                .setProperty("decision.pm-has-ready-product-handoff.result", constant(false))
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        One product handoff under review: Plane work item id/link, PO, priority, canonical acceptance page,
                        PM HANDOFF: READY content, and requested PM action. Promote selected handoff to pm-cycle before intake exits.
                        """))
                .to("tack:thought-enter?name=pm-intake&maxTokens=250")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Determine whether exactly one ready PO handoff exists now.
                                Select one product item only when it contains a current PM HANDOFF: READY comment.
                                Capture Plane item, priority, PO, canonical acceptance page, acceptance summary, unresolved
                                product questions, and requested PM action. If no ready handoff exists, do not invent acceptance
                                criteria, do not enter task planning, and optionally reply once to an active PO with one precise request.
                                """))
                .to("tack:act?id=pm-intake-review&toolProfile=" + ProjectManagementToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                                Does one current product item with PM HANDOFF: READY exist and have enough acceptance context
                                for PM task planning? Return false when no ready PO handoff exists.
                                """))
                .to("tack:decide?id=pm-has-ready-product-handoff")
                .to("tack:thought-exit?name=pm-intake");

        from("direct:pm-task-planning-loop")
                .routeId("pm-task-planning-loop")
                .setProperty("decision.pm-task-planning-done.result", constant(false))
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        One developer task being planned: Plane work item id/link, assignee, repository, canonical acceptance page,
                        acceptance criteria, dependencies, next code step, and expected review evidence.
                        """))
                .to("tack:thought-enter?name=pm-planning&maxTokens=250")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Turn the selected PM HANDOFF: READY slice into one small assigned To Do developer task.
                                Split oversized Plane content by moving details into smaller child items; keep parents as summaries.
                                Include assignee, priority, source repo, acceptance page, dependencies, next code step, review evidence,
                                and a DEV HANDOFF: READY comment. Do not create duplicates or leave ready work unassigned.
                                """))
                .to("tack:act?id=pm-actionable-dev-task&toolProfile=" + ProjectManagementToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                                Is task planning complete because one assigned actionable developer task is ready with
                                DEV HANDOFF: READY, or because one exact missing input/assignment blocker was durably recorded?
                                """))
                .to("tack:decide?id=pm-task-planning-done")
                .to("tack:thought-exit?name=pm-planning");

        from("direct:pm-team-sync")
                .routeId("pm-team-sync")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Read recent project chat and participate when a real coordination exchange helps.
                                Reply to direct questions, review/blocker handoffs, or ask one named question that unblocks work.
                                Avoid generic status; link the Plane item or product documentation when useful.
                                """))
                .to("tack:act?id=pm-team-chat-sync&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:pm-cycle-closeout")
                .routeId("pm-cycle-closeout")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Curate one small product documentation improvement only when it helps navigation or understanding.
                                Keep daily work state in Plane, leave ephemeral chat out, and split pages before they grow past a readable size.
                                """))
                .to("tack:act?id=pm-status-note&toolProfile=" + SourceControlToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                                Update one Plane work item only if verified status, risk, review requirement, PO INPUT REQUIRED:,
                                DEV HANDOFF: READY, PM REVIEW: READY, or DEV BLOCKER: changed. Do not depend on custom states or labels.
                                """))
                .to("tack:act?id=pm-release-risk-update&toolProfile=" + ProjectManagementToolProfile.ID);
    }
}
