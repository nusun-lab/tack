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
                .to("direct:pm-bootstrap")
                .setProperty("decision.project-manager-mission-complete.result", constant("no"))
                .loopDoWhile(exchangeProperty("decision.project-manager-mission-complete.result").isNotEqualTo("yes"))
                    .to("direct:pm-orientation")
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Choose the highest-value focus. Bootstrap and planning use their own write tools.
                            - bootstrap: discover or establish the mission project and channel when their readiness is unknown.
                            - planning: an engineer can start useful work once you assign, repair or create a Story for a
                              product-ready Epic with a verified repository baseline. Prefer unlocking the first work.
                            - defect-flow: a PO-validated to-do Defect lacks a repair assignee.
                            - onboarding: an active run is verified missing from the channel. Reconcile independently of delivery.
                            - documentation: the system repo exists and project basics need an update.
                            - coordination: a specific inbound question or verified cross-role blocker needs PM judgment.
                            - complete: required Epics are accepted and done and delivery blockers are resolved.
                            - wait: no action is useful until new evidence or another role's work arrives. Waiting for
                              the first Epic or repository baseline is normal after bootstrap.
                            Assignment identifies the owner. Only mission_roster establishes staffing. ARCH owns repository setup.
                            """))
                    .to("tack:decide?id=pm-focus&choices=bootstrap,planning,defect-flow,onboarding,documentation,"
                            + "coordination,complete,wait")
                    .toD("direct:pm-focus-${exchangeProperty.decision.pm-focus.result}")
                .end()
                .end()
                .to("tack:thought-exit?name=pm-root");

        from("direct:pm-orientation")
                .routeId("pm-orientation")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Resolve mission project and channel readiness first. If either needs bootstrap, stop discovery there.
                        Once the project is usable, inspect the highest-priority to-do or in-progress Epic and its ready work.
                        For an idle engineering queue, identify the first Story to assign or a missing Story to create.
                        Look up the documented repository map only if needed for that handoff. Check channel membership only
                        for changed active coverage from mission_roster. Distinguish an unfilled queue from missing staffing.
                        """ + engineeringIdentities))
                .to("tack:act?id=pm-orientation&toolProfile=" + WorkspaceReadonlyToolProfile.ID);

        from("direct:pm-focus-bootstrap")
                .routeId("pm-focus-bootstrap")
                .to("direct:pm-bootstrap");

        from("direct:pm-bootstrap")
                .routeId("pm-bootstrap")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Find the exact mission project and channel by anchor. Reuse existing matches and create any missing
                        resource. Verify the board once with list_project_columns and onboard verified missing active
                        teammates. Report exact project and channel locators as soon as both are usable.
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
                        QA discovers merged fixes from the linked PR. Leave in-progress repairs with their current owners.
                        """ + engineeringIdentities))
                .to("tack:act?id=pm-defect-flow&toolProfile=" + DeliveryManagementToolProfile.ID);

        from("direct:pm-flow")
                .routeId("pm-flow")
                .to("direct:pm-planning");

        from("direct:pm-focus-planning")
                .routeId("pm-focus-planning")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Exact Epic/task/assignee IDs and repository owner/repo pairs for Story coverage and dependencies.
                        Track what is prepared, executable or awaiting a named prerequisite.
                        """))
                .to("tack:thought-enter?name=pm-planning-focus&maxTokens=550")
                .to("direct:pm-planning")
                .to("tack:thought-exit?name=pm-planning-focus");

        from("direct:pm-planning")
                .routeId("pm-planning")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Deliver one executable Story before decomposing the rest of the Epic. Pick the highest-priority PO-owned
                        to-do or in-progress Epic with a verified component map. Read its acceptance and existing children.
                        Resolve the persistent DEV for component work or ARCH for system/cross-component work. Reuse a
                        usable partial Story and repair its assignment if needed. Otherwise create a planned Story with
                        that engineer's userId, one exact owner/repo, a small acceptance slice and a local verification target.
                        Attach story and the Epic subtask relation. With required incoming prerequisites done, promote
                        the assigned Story to to-do in this act. Verify its owner and status.
                        A to-do Epic is sufficient for its first executable Story. Feed additional Stories under in-progress Epics.
                        AgentRun presence, QA work and chat are independent. A PR waiting for merge leaves room for independent work.
                        Use blocks only for real prerequisites and repair incorrect relations after verifying direction and impact.
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
