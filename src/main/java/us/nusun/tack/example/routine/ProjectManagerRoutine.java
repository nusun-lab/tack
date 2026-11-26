package us.nusun.tack.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.tack.example.tool.DeliveryManagementToolProfile;
import us.nusun.tack.example.tool.ProjectDocumentationToolProfile;
import us.nusun.tack.example.tool.TeamChatToolProfile;
import us.nusun.tack.example.tool.WorkspaceCoordinationToolProfile;
import us.nusun.tack.example.tool.WorkspaceReadonlyToolProfile;
import us.nusun.tack.framework.persona.PersonaAccounts;
import us.nusun.tack.framework.routine.RoutineProvider;
import us.nusun.tack.framework.inference.LlmPassType;
import us.nusun.tack.framework.routine.RoutineDefinition;
import us.nusun.tack.framework.routine.TackHeaders;

import java.util.List;

@ApplicationScoped
public class ProjectManagerRoutine extends RouteBuilder implements RoutineProvider {
    public static final String ID = "software-team/project-manager";

    private final String engineeringIdentities;

    public ProjectManagerRoutine(PersonaAccounts accounts) {
        engineeringIdentities = """

                Resolve persistent engineers by exact Kaneo account email, then use their returned user IDs.
                DEV uses %s and ARCH uses %s.
                An unconfigured or unmatched email is a provisioning blocker.
                """.formatted(accounts.accounts().get("miles").email().orElse("unconfigured"),
                accounts.accounts().get("avery").email().orElse("unconfigured"));
    }

    @Override
    public List<RoutineDefinition> routines() {
        return List.of(routine());
    }

    public RoutineDefinition routine() {
        return RoutineDefinition.builder(ID)
                .name("Project Manager")
                .main("Manage delivery flow, synchronization, and ready-work buffers across the team.",
                        "direct:example-software-team-project-manager")
                .subroutine("bootstrap", "Establish shared coordination basics.", "direct:pm-bootstrap")
                .subroutine("planning", "Prepare active and ready Epics into executable Story work.", "direct:pm-planning")
                .subroutine("documentation", "Keep canonical project basics current in the system repository.",
                        "direct:pm-project-basics")
                .subroutine("flow", "Synchronize active delivery, defects, and handoffs.", "direct:pm-flow")
                .defaultLane("high-pass")
                .withLaneBinding(LlmPassType.SYNTHESIS, "low-pass")
                .withLaneBinding(LlmPassType.CONSOLIDATION, "low-pass")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-software-team-project-manager")
                .routeId("example-software-team-project-manager")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Mission anchor, verified Kaneo workspace ID, project ID/slug, user/assignee IDs, default lifecycle and
                        Mattermost team/channel IDs. Keep current roster parity, Gitea system/component owner/repo pairs,
                        actionable Epic/Story/Defect IDs, PR evidence and the next delivery condition.
                        """))
                .to("tack:thought-enter?name=pm-root&maxTokens=450")
                .setProperty("decision.project-manager-mission-complete.result", constant("no"))
                .loopDoWhile(exchangeProperty("decision.project-manager-mission-complete.result").isNotEqualTo("yes"))
                    .to("direct:pm-orientation")
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Choose the highest-value focus.
                            - bootstrap: verified missing mission project or channel, or unverified default lifecycle for the mission board.
                            - onboarding: mission_roster shows an active run whose persona is missing from the channel.
                            - defect-flow: a validated to-do Defect needs assignment or a merged fix awaits QA retest.
                            - active-flow: repair delivery ownership or dependencies, or feed an executable queue.
                            - planning: the documented repository map is ready and a to-do or active Epic needs Story coverage.
                            - documentation: the system repo exists and project basics need an update.
                            - coordination: a fresh delivery issue needs PM judgment.
                            - complete: required Epics are accepted and done and delivery blockers are resolved.
                            - wait: no action is useful until new evidence or another role's work arrives.
                            Assignment identifies the owner. Only mission_roster establishes staffing. ARCH owns repository setup.
                            """))
                    .to("tack:decide?id=pm-focus&choices=bootstrap,onboarding,documentation,defect-flow,"
                            + "active-flow,planning,coordination,complete,wait")
                    .toD("direct:pm-focus-${exchangeProperty.decision.pm-focus.result}")
                .end()
                .end()
                .to("tack:thought-exit?name=pm-root");

        from("direct:pm-orientation")
                .routeId("pm-orientation")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read active coverage from mission_roster. Compare those personas with the channel members
                        when parity is unknown or either set changed. Record active runs separately from persistent assignees.
                        Reuse verified default columns and the repository map. Look for actionable Epic coverage, ownership gaps,
                        validated Defects and planned Stories ready under active Epics. Use per-assignee queue filters where needed.
                        """ + engineeringIdentities))
                .to("tack:act?id=pm-orientation&toolProfile=" + WorkspaceReadonlyToolProfile.ID);

        from("direct:pm-focus-bootstrap")
                .routeId("pm-focus-bootstrap")
                .to("direct:pm-bootstrap");

        from("direct:pm-bootstrap")
                .routeId("pm-bootstrap")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Reuse or create the private mission project and channel using the mission anchor. Verify default
                        columns once with list_project_columns. Check access for the active roster and reconcile channel membership.
                        Send a kickoff with the project locator when the channel is first made usable.
                        """))
                .to("tack:act?id=pm-bootstrap&toolProfile=" + WorkspaceCoordinationToolProfile.ID);

        from("direct:pm-focus-onboarding")
                .routeId("pm-focus-onboarding")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Compare mission_roster active contacts with channel membership. Add verified missing active teammates
                        and read back changes. Report parity when already in sync.
                        """))
                .to("tack:act?id=pm-onboarding&toolProfile=" + WorkspaceCoordinationToolProfile.ID);

        from("direct:pm-focus-documentation")
                .routeId("pm-focus-documentation")
                .to("direct:pm-project-basics");

        from("direct:pm-project-basics")
                .routeId("pm-project-basics")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read the system README and architecture docs. Maintain only docs/project-basics.md as compact mission
                        navigation with the mission anchor, verified Kaneo project, Mattermost channel, Gitea repository map,
                        and role responsibilities. Reuse the docs branch/PR and leave integration to Architect.
                        """))
                .to("tack:act?id=pm-project-basics&toolProfile=" + ProjectDocumentationToolProfile.ID)
                .to("direct:pm-awareness");

        from("direct:pm-focus-defect-flow")
                .routeId("pm-focus-defect-flow")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Route one unassigned to-do Defect with verified PO validation, Epic relation and repair priority.
                        Assign the persistent DEV identity for component work or ARCH for system, integration, or cross-cutting work.
                        Leave it to-do for the engineer to claim. Deferred planned Defects stay with PO.
                        When the fix PR is verified merged, leave the Defect in-progress and surface retest evidence to QA.
                        """ + engineeringIdentities))
                .to("tack:act?id=pm-defect-flow&toolProfile=" + DeliveryManagementToolProfile.ID)
                .to("direct:pm-awareness");

        from("direct:pm-focus-active-flow")
                .routeId("pm-focus-active-flow")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Exact project, Epic/Story/Defect, assignee, relation and PR locators for the current queue changes.
                        Keep dependency evidence, remaining gaps and their next actionable conditions.
                        """))
                .to("tack:thought-enter?name=pm-active-flow-focus&maxTokens=500")
                .to("direct:pm-flow")
                .to("tack:thought-exit?name=pm-active-flow-focus")
                .to("direct:pm-awareness");

        from("direct:pm-flow")
                .routeId("pm-flow")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Restore delivery across in-progress Epics. Repair Story and validated executable Defect ownership first.
                        Route Epic ownership gaps to PO and Test ownership gaps to QA. Leave merged Story closure to its engineer.
                        Use filtered assignment queues to keep roughly 2–3 executable Stories per engineer when available.
                        Find eligible planned Stories with an in-progress Epic and all incoming prerequisites done.
                        Assign the persistent engineer identity before promoting to-do. Queue readiness comes from lifecycle,
                        dependencies and assignment. Reconcile staffing and chat separately.
                        PR integration waits do not consume coding capacity.
                        Repair an incorrect or cyclic relation only after verifying its ID, direction and consequences. Preserve
                        valid dependencies and continue independent work. Archive obsolete Stories with a reason when warranted.
                        """ + engineeringIdentities))
                .to("tack:act?id=pm-flow&toolProfile=" + DeliveryManagementToolProfile.ID);

        from("direct:pm-focus-planning")
                .routeId("pm-focus-planning")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Exact Epic/task/assignee IDs and repository owner/repo pairs for Story coverage and dependencies.
                        Track what is prepared, executable or awaiting a named prerequisite.
                        """))
                .to("tack:thought-enter?name=pm-planning-focus&maxTokens=550")
                .to("direct:pm-planning")
                .to("tack:thought-exit?name=pm-planning-focus")
                .to("direct:pm-awareness");

        from("direct:pm-planning")
                .routeId("pm-planning")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Use the Architect's system and component map to decompose to-do and in-progress PO-owned Epics.
                        Read the selected Epic's Gherkin and create only missing story-labeled implementation work.
                        Put engineering verification in the Story description. QA creates separate Test tasks.
                        Create each Story planned with its story label and one subtask relation from the Epic. Record repository
                        and implementation intent in its description. Add incoming blocks relations only for real prerequisites.
                        Verify the graph remains acyclic and repair incorrect relations with evidence.
                        Children of to-do Epics stay planned. For in-progress Epics, assign eligible Stories and promote to-do
                        after all prerequisites are done. Aim for 2–3 executable units per engineer when the work supports it.
                        Use DEV for component implementation and ARCH for system or cross-component work. Assignment uses the
                        persistent persona even when its AgentRun is absent. PO owns Epics and QA owns Tests.
                        """ + engineeringIdentities))
                .to("tack:act?id=pm-planning&toolProfile=" + DeliveryManagementToolProfile.ID);

        from("direct:pm-focus-coordination")
                .routeId("pm-focus-coordination")
                .to("direct:pm-awareness");

        from("direct:pm-awareness")
                .routeId("pm-awareness")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Use the current-mission shared channel. Send/reply once only when changed assignment, dependency,
                        defect routing, merge reconciliation, QA handoff, blocker, or WIP pressure will help another
                        routine act sooner. Chat is awareness only: acknowledgments, approval phrases, "green" signals,
                        and handoff messages never gate Kaneo lifecycle or Gitea integration. Missing channel access is
                        onboarding work, not a reason to use a DM. Avoid generic status.
                        """))
                .to("tack:act?id=pm-awareness&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:pm-focus-complete")
                .routeId("pm-focus-complete")
                .setProperty("decision.project-manager-mission-complete.result", constant("yes"));

        from("direct:pm-focus-wait")
                .routeId("pm-focus-wait")
                .delay(120000);
    }
}
