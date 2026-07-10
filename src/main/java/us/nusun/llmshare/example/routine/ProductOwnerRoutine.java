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
public class ProductOwnerRoutine extends RouteBuilder implements RoutineProvider {
    public static final String ID = "software-team/product-owner";
    public static final String PRODUCT_OWNER_ROUTINE_THOUGHT = "Durable product anchors: product documentation pages, current-work page, Plane project, source repository, team chat, persistent product blockers.";
    public static final String PO_BASICS_THOUGHT = "Current product-basics read: documentation repository, current-work page, Plane project, source repository, team chat, product navigation, blockers. Promote durable anchors to product-owner-routine before basics exits.";
    public static final String PO_CYCLE_THOUGHT = "Current PO pass: selected product Plane work item, canonical item page, acceptance status, priority/readiness, PM handoff request, next product action.";
    public static final String PO_BACKLOG_THOUGHT = "Current item selection: Plane work item id/link, owner, priority, status, blocker, canonical page. Promote selected item to po-cycle before backlog exits.";
    public static final String PO_ACCEPTANCE_THOUGHT = "Scope and acceptance work for selected item: goal, users, in/out of scope, acceptance criteria, decisions, open questions. Promote acceptance summary/page link to po-cycle.";
    public static final String PO_PRIORITY_THOUGHT = "Priority/status handoff: Plane work item status, priority, readiness for PM breakdown, remaining blocker, requested PM action.";

    @Inject RoutineSupport routine;

    @Override
    public List<RoutineDefinition> routines() {
        return List.of(routine());
    }

    public RoutineDefinition routine() {
        return RoutineDefinition.builder(ID)
                .name("Product Owner")
                .description("""
                        Clarify product focus through documentation-backed scope, acceptance criteria,
                        priority updates, decisions, and closeout.
                        """)
                .routeUri("direct:example-software-team-product-owner")
                .defaultLane("high-pass")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-software-team-product-owner")
                .routeId("example-software-team-product-owner")
                .process(routine.enterThought("product-owner-routine", PRODUCT_OWNER_ROUTINE_THOUGHT, 500))
                .to("direct:po-project-basics-loop")
                .to("direct:po-product-loop")
                .process(routine.exitThought("product-owner-routine"));

        from("direct:po-project-basics-loop")
                .routeId("po-project-basics-loop")
                .setProperty("productOwnerProjectBasicsCycle", constant(0))
                .setProperty("decision.product-owner-has-project-basics.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.product-owner-has-project-basics.result").isNotEqualTo(true))
                    .setProperty("productOwnerProjectBasicsCycle", simple("${exchangeProperty.productOwnerProjectBasicsCycle++}"))
                    .process(routine.enterThought("po-basics", PO_BASICS_THOUGHT, 300))
                    .process(routine.act("po-workspace-basics-read")
                            .prompt("""
                                    Verify project basics across the workspace without mutation.
                                    Use Gitea for the documentation index and direct project-basics/current-work/product links,
                                    Plane for project and work item status, and Mattermost for recent setup conversation.
                                    Capture bootstrap status, Git organization agent-workspace, documentation repository URL,
                                    team chat, Plane project, source repository, current work, blockers, and product page map.
                                    Do not scan whole projects, repositories, documentation repositories, or channels.
                                    """)
                            .toolProfile(WorkspaceReadonlyToolProfile.ID))
                    .to("direct:act")
                    .process(routine.decide("product-owner-has-project-basics")
                            .prompt("""
                                    Are project basics present and linked enough to do product work: bootstrap status,
                                    Git organization agent-workspace, team chat, Plane project, source repository under
                                    agent-workspace, current work, blockers, and product navigation?
                                    """))
                    .to("direct:decide")
                    .choice()
                        .when(exchangeProperty("decision.product-owner-has-project-basics.result").isNotEqualTo(true))
                            .to("direct:po-project-basics-blocker")
                    .end()
                    .process(routine.exitThought("po-basics"))
                .end();

        from("direct:po-project-basics-blocker")
                .routeId("po-project-basics-blocker")
                .process(routine.act("po-project-basics-blocker")
                        .prompt("""
                                Read the last 20 messages in the default or project Mattermost channel.
                                If PM/setup discussion exists, respond there only when a specific product/setup
                                answer is useful. Otherwise post one blocker asking PM to finish project basics
                                only if no current blocker exists. Include the documentation index link if known.
                                Do not start product work; keep rechecking project basics in the next loop.
                                """)
                        .toolProfile(TeamChatToolProfile.ID))
                .to("direct:act");

        from("direct:po-product-loop")
                .routeId("po-product-loop")
                .setProperty("productOwnerCycle", constant(0))
                .setProperty("decision.product-owner-done.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.product-owner-done.result").isNotEqualTo(true))
                    .setProperty("productOwnerCycle", simple("${exchangeProperty.productOwnerCycle++}"))
                    .process(routine.enterThought("po-cycle", PO_CYCLE_THOUGHT, 300))
                    .to("direct:po-team-chat-sync")
                    .to("direct:po-backlog-selection-loop")
                    .to("direct:po-acceptance-loop")
                    .to("direct:po-priority-status-loop")
                    .to("direct:po-cycle-closeout")
                    .process(routine.decide("product-owner-done")
                            .prompt("""
                                    Is this PO pass complete with scope, acceptance, priority,
                                    follow-up, and documentation links clear enough for PM and DEV?
                                    """))
                    .to("direct:decide")
                    .process(routine.exitThought("po-cycle"))
                .end();

        from("direct:po-team-chat-sync")
                .routeId("po-team-chat-sync")
                .process(routine.act("po-team-chat-sync")
                        .prompt("""
                                Read the last 20 project Mattermost messages from the project-basics link. Reply first to a direct
                                product, scope, priority, or acceptance question. If a clarification is needed,
                                ask at most one named question. Do not post generic status or duplicate documentation content.
                                """)
                        .toolProfile(TeamChatToolProfile.ID))
                .to("direct:act");

        from("direct:po-backlog-selection-loop")
                .routeId("po-backlog-selection-loop")
                .setProperty("poBacklogCycle", constant(0))
                .setProperty("decision.po-backlog-selection-done.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.po-backlog-selection-done.result").isNotEqualTo(true))
                    .setProperty("poBacklogCycle", simple("${exchangeProperty.poBacklogCycle++}"))
                    .process(routine.enterThought("po-backlog", PO_BACKLOG_THOUGHT, 250))
                    .process(routine.act("po-backlog-review")
                            .prompt("""
                                    In the Plane project from project basics, review one PO-owned product/backlog work item
                                    needing scope or acceptance. Do not select developer implementation tasks.
                                    Capture priority, status, owner, blocker, and canonical documentation page.
                                    """)
                            .toolProfile(ProjectManagementToolProfile.ID))
                    .to("direct:act")
                    .process(routine.act("po-product-context-read")
                            .prompt("""
                                    Read only the documentation index, current-work, the selected item page, and direct product links.
                                    Report missing or stale navigation instead of reading the whole documentation repository.
                                    """)
                            .toolProfile(SourceControlToolProfile.ID))
                    .to("direct:act")
                    .process(routine.decide("po-backlog-selection-done")
                            .prompt("""
                                    Is one product item selected with enough Plane and documentation context to refine it,
                                    or is the missing context clearly identified?
                                    """))
                    .to("direct:decide")
                    .process(routine.exitThought("po-backlog"))
                .end();

        from("direct:po-acceptance-loop")
                .routeId("po-acceptance-loop")
                .setProperty("poAcceptanceCycle", constant(0))
                .setProperty("decision.po-acceptance-done.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.po-acceptance-done.result").isNotEqualTo(true))
                    .setProperty("poAcceptanceCycle", simple("${exchangeProperty.poAcceptanceCycle++}"))
                    .process(routine.enterThought("po-acceptance", PO_ACCEPTANCE_THOUGHT, 250))
                    .process(routine.act("po-canonical-acceptance-update")
                            .prompt("""
                                    Update one canonical documentation page for the selected item. Include Plane work item link,
                                    owner, goal, users, in-scope, out-of-scope, compact Gherkin acceptance criteria,
                                    decisions, and open questions. Keep each criterion testable and tied to the Plane work item.
                                    Record one product decision or open question on the same canonical item page, or on a focused
                                    decisions page linked from that item. Keep it small and linked from current-work and the product index.
                                    """)
                            .toolProfile(SourceControlToolProfile.ID))
                    .to("direct:act")
                    .process(routine.decide("po-acceptance-done")
                            .prompt("""
                                    Are scope and acceptance criteria usable, linked, and small enough for PM breakdown?
                                    """))
                    .to("direct:decide")
                    .process(routine.exitThought("po-acceptance"))
                .end();

        from("direct:po-priority-status-loop")
                .routeId("po-priority-status-loop")
                .setProperty("poPriorityCycle", constant(0))
                .setProperty("decision.po-priority-status-done.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.po-priority-status-done.result").isNotEqualTo(true))
                    .setProperty("poPriorityCycle", simple("${exchangeProperty.poPriorityCycle++}"))
                    .process(routine.enterThought("po-priority", PO_PRIORITY_THOUGHT, 250))
                    .process(routine.act("po-plane-handoff-update")
                            .prompt("""
                                    Update priority or product readiness on one Plane work item after verifying current status.
                                    If acceptance is ready, move or annotate it as ready for PM breakdown only if that workflow
                                    state exists. Add a compact handoff note for PM with canonical documentation page, priority,
                                    acceptance summary, remaining questions, and requested PM action. Do not create, assign,
                                    or start developer tasks.
                                    """)
                            .toolProfile(ProjectManagementToolProfile.ID))
                    .to("direct:act")
                    .process(routine.decide("po-priority-status-done")
                            .prompt("""
                                    Is the product item ready for PM handoff, with priority/readiness in Plane
                                    and the canonical documentation page linked?
                                    """))
                    .to("direct:decide")
                    .process(routine.exitThought("po-priority"))
                .end();

        from("direct:po-cycle-closeout")
                .routeId("po-cycle-closeout")
                .process(routine.act("po-team-chat-closeout")
                        .prompt("""
                                Read the last 20 messages in the project Mattermost channel from project basics.
                                Post only if the canonical item page changed materially or a teammate is waiting.
                                Include the canonical documentation page and who it unblocks; otherwise stay quiet.
                                """)
                        .toolProfile(TeamChatToolProfile.ID))
                .to("direct:act")
                .process(routine.act("po-follow-up-note")
                        .prompt("""
                                If needed, add one small follow-up note to the canonical documentation page.
                                Preserve navigation: current-work, product index, Plane work item, and decisions links.
                                Capture durable product outcomes from Mattermost; leave ephemeral discussion in Mattermost.
                                """)
                        .toolProfile(SourceControlToolProfile.ID))
                .to("direct:act");
    }
}
