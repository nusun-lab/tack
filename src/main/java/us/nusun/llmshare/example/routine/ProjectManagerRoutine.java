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
                        "Provision and verify an agile project, wiki repo, and chat channel before delivery work management starts.",
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
                        .to("direct:pm-work-intake-loop")
                        .to("direct:pm-task-planning-loop")
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
                                team chat, Plane project, source repository, documentation repository, roster.
                                Do not scan whole projects, repositories, documentation repositories, or channels.
                                """))
                .to("tack:act?id=pm-workspace-basics-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                                Are project basics verified and current enough for delivery coordination, with
                                Git organization agent-workspace and a working link for team chat, Plane project,
                                documentation repository, source repository, and roster?
                                """))
                .to("tack:decide?id=collaboration-setup-complete");

        from("direct:pm-project-bootstrap")
                .routeId("pm-project-bootstrap")
                .setProperty("collaborationSetupAttempt", constant(0))
                .loopDoWhile(exchangeProperty("decision.collaboration-setup-complete.result").isNotEqualTo(true))
                    .setProperty("collaborationSetupAttempt", simple("${exchangeProperty.collaborationSetupAttempt++}"))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Current bootstrap checklist: documentation repository, team chat, Plane project, source repository,
                        and roster. Promote final URLs to project-manager-routine before bootstrap exits.
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
                                    Invite the team and David Gordon (@aphelio).
                                    Never create a duplicate channel.
                                    Do not post setup status yet.
                                    """))
                    .to("tack:act?id=chat-channel-setup&toolProfile=" + TeamChatToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Create or update the canonical documentation bootstrap pages.
                                    Index: navigation only, linking project-basics.
                                    Project-basics: mission, Git organization agent-workspace, documentation repository URL,
                                    team chat, Plane project, source repository, roster and roles.
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
                                    source repository, and roster?
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
                    .to("direct:pm-work-intake-loop")
                    .to("direct:pm-task-planning-loop")
                    .to("direct:pm-team-sync")
                    .to("direct:pm-cycle-closeout")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is this PM pass complete with setup, current work, status movement,
                                    owners, and documentation links clear enough for PO and DEV?
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

        from("direct:pm-work-intake-loop")
                .routeId("pm-work-intake-loop")
                .setProperty("pmWorkIntakeCycle", constant(0))
                .setProperty("decision.pm-work-intake-done.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.pm-work-intake-done.result").isNotEqualTo(true))
                    .setProperty("pmWorkIntakeCycle", simple("${exchangeProperty.pmWorkIntakeCycle++}"))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        One product handoff under review: Plane work item id/link, owner, priority, canonical acceptance page,
                        readiness. Promote selected handoff to pm-cycle before intake exits.
                        """))
                    .to("tack:thought-enter?name=pm-intake&maxTokens=250")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Review exactly one PO work item marked ready for PM breakdown or waiting on PM.
                                    Capture status, priority, product owner, canonical acceptance page, and requested handoff.
                                    Verify current status before changing it. If the owner or acceptance page is missing, leave status
                                    or mark blocked only if that state exists. Do not start developer work.
                                    """))
                    .to("tack:act?id=pm-intake-review&toolProfile=" + ProjectManagementToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is intake complete for now: one PO handoff reviewed, readiness clear,
                                    and the canonical acceptance page recorded or requested?
                                    """))
                    .to("tack:decide?id=pm-work-intake-done")
                    .to("tack:thought-exit?name=pm-intake")
                .end();

        from("direct:pm-task-planning-loop")
                .routeId("pm-task-planning-loop")
                .setProperty("pmTaskPlanningCycle", constant(0))
                .setProperty("decision.pm-task-planning-done.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.pm-task-planning-done.result").isNotEqualTo(true))
                    .setProperty("pmTaskPlanningCycle", simple("${exchangeProperty.pmTaskPlanningCycle++}"))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        One developer task being planned: Plane work item id/link, assignee, repository, canonical documentation page,
                        acceptance. Promote created/updated task to pm-cycle before planning exits.
                        """))
                    .to("tack:thought-enter?name=pm-planning&maxTokens=250")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Create or update exactly one small developer implementation Plane work item from one ready PO item.
                                    Put it in to-do only when assignee, priority, agent-workspace source repository, canonical page,
                                    acceptance, dependencies, and one concrete next code step are present.
                                    Do not plan multiple tasks in this step.
                                    """))
                    .to("tack:act?id=pm-actionable-dev-task&toolProfile=" + ProjectManagementToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is task planning complete for now with at least one assigned actionable to-do?
                                    """))
                    .to("tack:decide?id=pm-task-planning-done")
                    .to("tack:thought-exit?name=pm-planning")
                .end();

        from("direct:pm-team-sync")
                .routeId("pm-team-sync")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Read the last 20 project Mattermost messages from the project-basics link.
                                Reply first to a direct  PM question, or handoff. If no reply is needed, ask at most one named
                                coordination question. Do not post generic status.
                                Link the Plane work item or canonical documentation page.
                                """))
                .to("tack:act?id=pm-team-chat-sync&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:pm-cycle-closeout")
                .routeId("pm-cycle-closeout")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Update current-work or one small status page and keep it linked from the index.
                                Record owner, Plane state, Plane work item link, canonical documentation page,
                                and any durable outcome from recent Mattermost messages. Leave ephemeral chatter out.
                                """))
                .to("tack:act?id=pm-status-note&toolProfile=" + SourceControlToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                                Update one Plane work item only if verified status, risk, or blocker changed.
                                Preserve explicit status flow: to-do, in-progress, review/done, or blocked as defined.
                                """))
                .to("tack:act?id=pm-release-risk-update&toolProfile=" + ProjectManagementToolProfile.ID);
    }
}
