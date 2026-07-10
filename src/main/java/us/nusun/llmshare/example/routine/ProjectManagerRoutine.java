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
import us.nusun.llmshare.example.tool.WorkspaceCoordinationToolProfile;
import us.nusun.llmshare.example.tool.WorkspaceReadonlyToolProfile;

@ApplicationScoped
public class ProjectManagerRoutine extends RouteBuilder implements RoutineProvider {
    public static final String ID = "software-team/project-manager";
    public static final String PROJECT_MANAGER_ROUTINE_THOUGHT = "Durable PM project anchors: documentation index URL/path, project-basics/current-work pages, team chat channel, Plane project, source repository, roster, persistent setup blockers.";
    public static final String PM_BOOTSTRAP_THOUGHT = "Current bootstrap checklist: documentation repository, team chat, Plane project, source repository, roster, blocker owner, next bootstrap action. Promote final URLs/blockers to project-manager-routine before bootstrap exits.";
    public static final String PM_CYCLE_THOUGHT = "Current PM pass: selected product handoff, selected developer task, status movement, owners, blockers, risks, next team action.";
    public static final String PM_INTAKE_THOUGHT = "One product handoff under review: Plane work item id/link, owner, priority, canonical acceptance page, readiness/blocker. Promote selected handoff to pm-cycle before intake exits.";
    public static final String PM_PLANNING_THOUGHT = "One developer task being planned: Plane work item id/link, assignee, repository, canonical documentation page, acceptance, next code step, blocker. Promote created/updated task to pm-cycle before planning exits.";

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
                        sequencing, documentation coordination state, status, risks, and PM closeout.
                        """)
                .routeUri("direct:example-software-team-project-manager")
                .defaultLane("high-pass")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-software-team-project-manager")
                .routeId("example-software-team-project-manager")
                .process(routine.enterThought("project-manager-routine", PROJECT_MANAGER_ROUTINE_THOUGHT, 500))
                .process(routine.act("pm-workspace-basics-read")
                        .prompt("""
                                Verify project basics across the workspace without mutation.
                                Use Plane for the project and planned work, Gitea for the documentation and source repositories,
                                and Mattermost for recent setup conversation. Open only the documentation index and direct
                                project-basics/current-work links when needed. Capture bootstrap status, Git organization
                                agent-workspace, team chat, Plane project, source repository, documentation repository, and roster.
                                Do not scan whole projects, repositories, documentation repositories, or channels.
                                """)
                        .toolProfile(WorkspaceReadonlyToolProfile.ID))
                .to("direct:act")
                .process(routine.decide("collaboration-setup-complete")
                        .prompt("""
                                Are project basics verified and current enough for delivery coordination, with
                                Git organization agent-workspace and a working link or explicit blocker owner for
                                team chat, Plane project, documentation repository, source repository, and roster?
                                """))
                .to("direct:decide")
                .choice()
                    .when(exchangeProperty("decision.collaboration-setup-complete.result").isNotEqualTo(true))
                        .to("direct:pm-project-bootstrap")
                .end()
                .to("direct:pm-coordination-loop")
                .process(routine.exitThought("project-manager-routine"));

        from("direct:pm-project-bootstrap")
                .routeId("pm-project-bootstrap")
                .setProperty("collaborationSetupAttempt", constant(0))
                .loopDoWhile(exchangeProperty("decision.collaboration-setup-complete.result").isNotEqualTo(true))
                    .setProperty("collaborationSetupAttempt", simple("${exchangeProperty.collaborationSetupAttempt++}"))
                    .process(routine.enterThought("pm-bootstrap", PM_BOOTSTRAP_THOUGHT, 300))
                    .process(routine.act("workspace-artifact-provisioning")
                            .prompt("""
                                    Search before creating artifacts. Create or confirm exactly one public Plane project,
                                    exactly one shared markdown documentation repository under Git organization agent-workspace,
                                    and exactly one target source repository under agent-workspace. Never create repositories
                                    in a personal namespace. Never create Gitea issues, labels, milestones, or time tracking.
                                    Do not write files, branches, pull requests, tags, or releases. Preserve existing useful
                                    repositories and Plane project state, and record any access blocker with owner.
                                    """)
                            .toolProfile(WorkspaceCoordinationToolProfile.ID))
                    .to("direct:act")
                    .process(routine.act("chat-channel-setup")
                            .prompt("""
                                    Create or confirm one private project Mattermost channel.
                                    Invite the team and David Gordon (@aphelio).
                                    Never create a duplicate channel.
                                    Do not post setup status yet.
                                    """)
                            .toolProfile(TeamChatToolProfile.ID))
                    .to("direct:act")
                    .process(routine.act("documentation-project-index")
                            .prompt("""
                                    Create or update the canonical documentation bootstrap pages.
                                    Index: navigation only, linking project-basics.
                                    Project-basics: mission, Git organization agent-workspace, documentation repository URL,
                                    team chat, Plane project, source repository, roster and roles.
                                    Before marking any project basic missing, search active thoughts for a verified
                                    current-mission anchor for that artifact.
                                    """)
                            .toolProfile(SourceControlToolProfile.ID))
                    .to("direct:act")
                    .process(routine.act("documentation-bootstrap-audit")
                            .prompt("""
                                    Read only the documentation index and project-basics page.
                                    Verify every project basic has a working link.
                                    Confirm documentation and source repositories are under agent-workspace, not a personal namespace.
                                    Before marking any project basic missing, search active thoughts for a verified
                                    current-mission anchor for that artifact.
                                    """)
                            .toolProfile(SourceControlToolProfile.ID))
                    .to("direct:act")
                    .process(routine.act("collaboration-setup-message")
                            .prompt("""
                                    Read the last 20 messages in the project Mattermost channel, or default channel if absent.
                                    If someone asked about setup, answer that thread. Otherwise post only if basics are missing,
                                    stale, or blocked. Keep Mattermost to a concise pointer; Plane and Gitea are authoritative.
                                    Include the project-basics documentation link and call out any bootstrap blockers once.
                                    """)
                            .toolProfile(TeamChatToolProfile.ID))
                    .to("direct:act")
                    .process(routine.decide("collaboration-setup-complete")
                            .prompt("""
                                    After this bootstrap pass, does the documentation index and project-basics page link
                                    Git organization agent-workspace, team chat, Plane project, documentation repository,
                                    source repository, and roster?
                                    """))
                    .to("direct:decide")
                    .process(routine.exitThought("pm-bootstrap"))
                .end();

        from("direct:pm-coordination-loop")
                .routeId("pm-coordination-loop")
                .setProperty("projectManagerCycle", constant(0))
                .setProperty("decision.project-manager-done.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.project-manager-done.result").isNotEqualTo(true))
                    .setProperty("projectManagerCycle", simple("${exchangeProperty.projectManagerCycle++}"))
                    .process(routine.enterThought("pm-cycle", PM_CYCLE_THOUGHT, 300))
                    .to("direct:pm-documentation-status-sync")
                    .to("direct:pm-work-intake-loop")
                    .to("direct:pm-task-planning-loop")
                    .to("direct:pm-team-sync")
                    .to("direct:pm-cycle-closeout")
                    .process(routine.decide("project-manager-done")
                            .prompt("""
                                    Is this PM pass complete with setup, current work, status movement,
                                    blockers, owners, and documentation links clear enough for PO and DEV?
                                    """))
                    .to("direct:decide")
                    .process(routine.exitThought("pm-cycle"))
                .end();

        from("direct:pm-documentation-status-sync")
                .routeId("pm-documentation-status-sync")
                .process(routine.act("pm-workspace-status-read")
                        .prompt("""
                                Read only mission-relevant status anchors across the workspace.
                                Use Gitea for the documentation index, project-basics, current-work, and direct status links;
                                Plane for owners, blockers, priorities, and work item state; and Mattermost only for recent
                                handoff context. Capture owners, blockers, Plane work item links, canonical documentation pages,
                                agent-workspace repository links, and broken navigation.
                                """)
                        .toolProfile(WorkspaceReadonlyToolProfile.ID))
                .to("direct:act");

        from("direct:pm-work-intake-loop")
                .routeId("pm-work-intake-loop")
                .setProperty("pmWorkIntakeCycle", constant(0))
                .setProperty("decision.pm-work-intake-done.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.pm-work-intake-done.result").isNotEqualTo(true))
                    .setProperty("pmWorkIntakeCycle", simple("${exchangeProperty.pmWorkIntakeCycle++}"))
                    .process(routine.enterThought("pm-intake", PM_INTAKE_THOUGHT, 250))
                    .process(routine.act("pm-intake-review")
                            .prompt("""
                                    In the Plane project from project basics, review one PO work item marked ready for PM breakdown
                                    or explicitly waiting on PM. Capture status, priority, product owner, blocker,
                                    canonical acceptance documentation page, and requested handoff.
                                    Verify current status before changing it. If product owner and canonical acceptance page
                                    are present, keep the item ready for planning. If not, leave status or mark blocked only
                                    if that state exists. Do not start developer work in this intake step.
                                    """)
                            .toolProfile(ProjectManagementToolProfile.ID))
                    .to("direct:act")
                    .process(routine.decide("pm-work-intake-done")
                            .prompt("""
                                    Is intake complete for now: one PO handoff reviewed, readiness clear,
                                    and the canonical acceptance page recorded or requested?
                                    """))
                    .to("direct:decide")
                    .process(routine.exitThought("pm-intake"))
                .end();

        from("direct:pm-task-planning-loop")
                .routeId("pm-task-planning-loop")
                .setProperty("pmTaskPlanningCycle", constant(0))
                .setProperty("decision.pm-task-planning-done.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.pm-task-planning-done.result").isNotEqualTo(true))
                    .setProperty("pmTaskPlanningCycle", simple("${exchangeProperty.pmTaskPlanningCycle++}"))
                    .process(routine.enterThought("pm-planning", PM_PLANNING_THOUGHT, 250))
                    .process(routine.act("pm-actionable-dev-task")
                            .prompt("""
                                    Create or update exactly one small developer implementation Plane work item from a ready PO item.
                                    The task starts in to-do only when it has a named assignee, priority, source repository under
                                    agent-workspace, canonical documentation page, acceptance, predecessor or dependency notes,
                                    and a concrete next code step. Set predecessor, next named owner, and unblock condition for
                                    that one task. PM owns assignment, to-do readiness, and blocked planning states. DEV owns
                                    to-do -> in-progress and in-progress -> review/done transitions. Annotate missing scope,
                                    assignee, repository, canonical page, or blockers; move to blocked only if the exact workflow
                                    state exists.
                                    """)
                            .toolProfile(ProjectManagementToolProfile.ID))
                    .to("direct:act")
                    .process(routine.decide("pm-task-planning-done")
                            .prompt("""
                                    Is task planning complete for now with at least one assigned actionable to-do
                                    or a precise blocker assigned to PM or PO?
                                    """))
                    .to("direct:decide")
                    .process(routine.exitThought("pm-planning"))
                .end();

        from("direct:pm-team-sync")
                .routeId("pm-team-sync")
                .process(routine.act("pm-team-chat-sync")
                        .prompt("""
                                Read the last 20 project Mattermost messages from the project-basics link. Reply first to a direct
                                PM question, blocker, or handoff. If no reply is needed, ask at most one named
                                coordination question. Do not post generic status. Link the Plane work item or canonical documentation page.
                                """)
                        .toolProfile(TeamChatToolProfile.ID))
                .to("direct:act");

        from("direct:pm-cycle-closeout")
                .routeId("pm-cycle-closeout")
                .process(routine.act("pm-status-note")
                        .prompt("""
                                Update current-work or one small status page and keep it linked from the index.
                                Record owner, Plane state, blocker, Plane work item link, canonical documentation page,
                                and any durable outcome from recent Mattermost messages. Leave ephemeral chatter out.
                                """)
                        .toolProfile(SourceControlToolProfile.ID))
                .to("direct:act")
                .process(routine.act("pm-release-risk-update")
                        .prompt("""
                                Update one Plane work item only if verified status, risk, or blocker changed.
                                Preserve explicit status flow: to-do, in-progress, review/done, or blocked as defined.
                                """)
                        .toolProfile(ProjectManagementToolProfile.ID))
                .to("direct:act");
    }
}
