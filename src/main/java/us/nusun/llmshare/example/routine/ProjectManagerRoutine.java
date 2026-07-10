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
    public static final String PROJECT_MANAGER_ROUTINE_THOUGHT = "Durable PM project anchors: documentation index URL/path, project-basics/current-work pages, team chat channel, project tracking workspace, source repo, roster, persistent setup blockers.";
    public static final String PM_BOOTSTRAP_THOUGHT = "Current bootstrap checklist: documentation workspace, team chat, project tracking workspace, source repo, roster, blocker owner, next bootstrap action. Promote final URLs/blockers to project-manager-routine before bootstrap exits.";
    public static final String PM_CYCLE_THOUGHT = "Current PM pass: selected product handoff, selected developer task, status movement, owners, blockers, risks, next team action.";
    public static final String PM_INTAKE_THOUGHT = "One product handoff under review: work item id/link, owner, priority, canonical acceptance page, readiness/blocker. Promote selected handoff to pm-cycle before intake exits.";
    public static final String PM_PLANNING_THOUGHT = "One developer task being planned: task id/link, assignee, repo, canonical documentation page, acceptance, next code step, blocker. Promote created/updated task to pm-cycle before planning exits.";

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
                        sequencing, wiki coordination state, status, risks, and PM closeout.
                        """)
                .routeUri("direct:example-software-team-project-manager")
                .defaultToolRuntime("tool-high")
                .defaultSynthesisRuntime("synthesis-high")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-software-team-project-manager")
                .routeId("example-software-team-project-manager")
                .process(routine.enterThought("project-manager-routine", PROJECT_MANAGER_ROUTINE_THOUGHT, 500))
                .process(routine.act("pm-wiki-basics-read")
                        .prompt("""
                                Open the wiki index only; follow project-basics and current-work links if needed.
                                Capture wiki URL, bootstrap status, Git organization agent-workspace, chat,
                                PM project, source repo, and roster.
                                Do not scan the whole wiki; missing navigation is a setup blocker.
                                """)
                        .toolProfile("source-control"))
                .to("direct:act")
                .process(routine.decide("collaboration-setup-complete")
                        .prompt("""
                                Are project basics present, marked current, and linked in the wiki, with
                                Git organization agent-workspace and a working link or explicit blocker owner
                                for chat, PM project, wiki, source repo, roster.
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
                    .process(routine.act("wiki-repo-setup")
                            .prompt("""
                                    Create or confirm one shared markdown wiki repository under Git organization
                                    agent-workspace only. Never create it in a personal namespace.
                                    Never duplicate. Keep the wiki URL available for every later update.
                                    If an agent-workspace wiki exists for the project, reuse it and preserve
                                    existing useful pages. If agent-workspace is inaccessible, record the blocker and owner.
                                    """)
                            .toolProfile("source-control"))
                    .to("direct:act")
                    .process(routine.act("chat-channel-setup")
                            .prompt("""
                                    Create or confirm one private project chat channel.
                                    Invite the team and David Gordon (@aphelio).
                                    Never create a duplicate channel.
                                    Do not post setup status yet.
                                    """)
                            .toolProfile("team-chat"))
                    .to("direct:act")
                    .process(routine.act("project-management-setup")
                            .prompt("""
                                    Create or confirm one public PM project.
                                    Never duplicate a project if one exists for the mission already.
                                    Do not attempt to add members, membership is opt-in.
                                    Do not start creating content. Provision the project only.
                                    """)
                            .toolProfile("project-management"))
                    .to("direct:act")
                    .process(routine.act("source-repository-plan")
                            .prompt("""
                                    Define or confirm the target source repository name and URL under Git organization
                                    agent-workspace only. Never create project repos in a personal namespace.
                                    Never duplicate; record blockers if the agent-workspace repo cannot be confirmed.
                                    """)
                            .toolProfile("source-control"))
                    .to("direct:act")
                    .process(routine.act("wiki-project-index")
                            .prompt("""
                                    Create or update the wiki bootstrap pages.
                                    Index: navigation only, linking project-basics
                                    Project-basics: mission, Git organization agent-workspace, wiki URL, chat,
                                    PM project, source repo, roster and roles.
                                    Before marking any project basic missing, search active thoughts for a verified
                                    current-mission anchor for that artifact.
                                    """)
                            .toolProfile("source-control"))
                    .to("direct:act")
                    .process(routine.act("wiki-bootstrap-audit")
                            .prompt("""
                                    Read only the wiki index and project-basics
                                    Verify every project basic has a working link
                                    Confirm wiki and source repos are under agent-workspace, not a personal namespace.
                                    Before marking any project basic missing, search active thoughts for a verified
                                    current-mission anchor for that artifact.
                                    """)
                            .toolProfile("source-control"))
                    .to("direct:act")
                    .process(routine.act("project-basics-announcement")
                            .prompt("""
                                    Read the last 20 messages in the project channel, or default channel if absent.
                                    If someone asked about setup, answer that message. Otherwise post only if basics
                                    are missing or stale. Keep chat to a pointer; the wiki index is source of truth.
                                    Include the project-basics link and call out any bootstrap blockers.
                                    """)
                            .toolProfile("team-chat"))
                    .to("direct:act")
                    .process(routine.act("collaboration-setup-gap")
                            .prompt("""
                                    If setup is incomplete, respond to the latest relevant setup question or post the blockers.
                                    Name the missing project, repo, invitation, permission, or wiki link. Do not repeat basics.
                                    """)
                            .toolProfile("team-chat"))
                    .to("direct:act")
                    .process(routine.decide("collaboration-setup-complete")
                            .prompt("""
                                    After this bootstrap pass, does the wiki index and project-basics page link
                                    Git organization agent-workspace, chat, PM project, wiki, source repo, and roster?
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
                    .to("direct:pm-wiki-status-sync")
                    .to("direct:pm-work-intake-loop")
                    .to("direct:pm-task-planning-loop")
                    .to("direct:pm-team-sync")
                    .to("direct:pm-cycle-closeout")
                    .process(routine.decide("project-manager-done")
                            .prompt("""
                                    Is this PM pass complete with setup, current work, status movement,
                                    blockers, owners, and wiki links clear enough for PO and DEV?
                                    """))
                    .to("direct:decide")
                    .process(routine.exitThought("pm-cycle"))
                .end();

        from("direct:pm-wiki-status-sync")
                .routeId("pm-wiki-status-sync")
                .process(routine.act("pm-wiki-status-read")
                        .prompt("""
                                Read only the wiki index, project-basics, current-work, and direct status links.
                                Capture owners, blockers, PM item links, canonical wiki pages,
                                agent-workspace repo links, and broken navigation.
                                """)
                        .toolProfile("source-control"))
                .to("direct:act");

        from("direct:pm-work-intake-loop")
                .routeId("pm-work-intake-loop")
                .setProperty("pmWorkIntakeCycle", constant(0))
                .setProperty("decision.pm-work-intake-done.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.pm-work-intake-done.result").isNotEqualTo(true))
                    .setProperty("pmWorkIntakeCycle", simple("${exchangeProperty.pmWorkIntakeCycle++}"))
                    .process(routine.enterThought("pm-intake", PM_INTAKE_THOUGHT, 250))
                    .process(routine.act("pm-po-item-review")
                            .prompt("""
                                    In the PM project from the wiki, review one PO item marked ready for PM breakdown
                                    or explicitly waiting on PM. Capture status, priority, product owner, blocker,
                                    canonical acceptance wiki page, and requested handoff.
                                    """)
                            .toolProfile("project-management"))
                    .to("direct:act")
                    .process(routine.act("pm-readiness-status")
                            .prompt("""
                                    Verify current status before changing it.
                                    If product owner and canonical acceptance page are present, keep the PO item
                                    ready for planning. If not, leave status or mark blocked only if that state exists.
                                    Do not start developer work in this intake step.
                                    """)
                            .toolProfile("project-management"))
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
                    .process(routine.act("pm-task-breakdown")
                            .prompt("""
                                    Create or update one small developer implementation task from a ready PO item.
                                    New developer work starts in to-do only when it has a named assignee,
                                    priority, source repo under agent-workspace, canonical wiki page,
                                    and acceptance.
                                    """)
                            .toolProfile("project-management"))
                    .to("direct:act")
                    .process(routine.act("pm-task-sequencing")
                            .prompt("""
                                    Set predecessor, next named owner, and unblock condition for one task.
                                    PM owns assignment, to-do readiness, and blocked planning states.
                                    DEV owns to-do -> in-progress and in-progress -> review/done transitions.
                                    """)
                            .toolProfile("project-management"))
                    .to("direct:act")
                    .process(routine.act("pm-actionability-check")
                            .prompt("""
                                    Check one assigned to-do task for scope, assignee, agent-workspace repo,
                                    canonical wiki page, and blockers.
                                    Annotate the task; move to blocked only if the exact workflow state exists.
                                    """)
                            .toolProfile("project-management"))
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
                                Read the last 20 project chat messages from the wiki link. Reply first to a direct
                                PM question, blocker, or handoff. If no reply is needed, ask at most one named
                                coordination question. Do not post generic status. Link the PM item or wiki page.
                                """)
                        .toolProfile("team-chat"))
                .to("direct:act");

        from("direct:pm-cycle-closeout")
                .routeId("pm-cycle-closeout")
                .process(routine.act("pm-status-note")
                        .prompt("""
                                Update current-work or one small status page and keep it linked from the index.
                                Record owner, PM state, blocker, PM item link, canonical wiki page,
                                and any durable outcome from recent chat. Leave ephemeral chatter out.
                                """)
                        .toolProfile("source-control"))
                .to("direct:act")
                .process(routine.act("pm-release-risk-update")
                        .prompt("""
                                Update one PM item only if verified status, risk, or blocker changed.
                                Preserve explicit status flow: to-do, in-progress, review/done, or blocked as defined.
                                """)
                        .toolProfile("project-management"))
                .to("direct:act");
    }
}
