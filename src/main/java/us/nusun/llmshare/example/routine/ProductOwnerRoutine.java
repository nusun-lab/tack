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
public class ProductOwnerRoutine extends RouteBuilder implements RoutineProvider {
    public static final String ID = "software-team/product-owner";

    @Override
    public List<RoutineDefinition> routines() {
        return List.of(routine());
    }

    public RoutineDefinition routine() {
        return RoutineDefinition.builder(ID)
                .name("Product Owner")
                .main("""
                        Full Product Owner routine: self-directed product ownership cycle. Gather current project context,
                        select product work, refine scope and acceptance, update priority/readiness, hand off to PM,
                        communicate only useful product guidance, and close out durable documentation.
                        """, "direct:example-software-team-product-owner")
                .subroutine(
                        "acceptance-refinement",
                        "Refine one product item with scope and acceptance criteria.",
                        "direct:example-software-team-product-owner-acceptance-refinement")
                .subroutine(
                        "priority-handoff",
                        "Update product priority and hand off one item for PM breakdown.",
                        "direct:example-software-team-product-owner-priority-handoff")
                .defaultLane("high-pass")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-software-team-product-owner")
                .routeId("example-software-team-product-owner")
                .to("direct:po-product-owner-root-enter")
                .to("direct:po-project-basics-loop")
                .setProperty("productOwnerCycle", constant(0))
                .setProperty("decision.product-owner-done.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.product-owner-done.result").isNotEqualTo(true))
                    .setProperty("productOwnerCycle", simple("${exchangeProperty.productOwnerCycle++}"))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Current PO pass: selected product Plane work item, canonical item page, acceptance status, priority/readiness, PM handoff request, next product action.
                        """))
                    .to("tack:thought-enter?name=po-cycle&maxTokens=300")
                    .to("direct:po-team-chat-sync")
                    .to("direct:po-backlog-selection-loop")
                    .to("direct:po-acceptance-loop")
                    .to("direct:po-priority-status-loop")
                    .to("direct:po-cycle-closeout")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is this PO pass complete with scope, acceptance, priority,
                                    follow-up, and documentation links clear enough for PM and DEV?
                                    """))
                    .to("tack:decide?id=product-owner-done")
                    .to("tack:thought-exit?name=po-cycle")
                .end()
                .to("direct:po-product-owner-root-exit");

        from("direct:example-software-team-product-owner-acceptance-refinement")
                .routeId("example-software-team-product-owner-acceptance-refinement")
                .to("direct:po-product-owner-root-enter")
                .to("direct:po-project-basics-loop")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Focused PO acceptance pass: selected product item, canonical page, scope, users, acceptance criteria, decisions, and open questions.
                        """))
                .to("tack:thought-enter?name=po-cycle&maxTokens=300")
                .to("direct:po-team-chat-sync")
                .to("direct:po-backlog-selection-loop")
                .to("direct:po-acceptance-loop")
                .to("direct:po-cycle-closeout")
                .to("tack:thought-exit?name=po-cycle")
                .to("direct:po-product-owner-root-exit");

        from("direct:example-software-team-product-owner-priority-handoff")
                .routeId("example-software-team-product-owner-priority-handoff")
                .to("direct:po-product-owner-root-enter")
                .to("direct:po-project-basics-loop")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Focused PO handoff pass: selected product item, priority, readiness, PM request, Plane state, and canonical page.
                        """))
                .to("tack:thought-enter?name=po-cycle&maxTokens=300")
                .to("direct:po-team-chat-sync")
                .to("direct:po-backlog-selection-loop")
                .to("direct:po-priority-status-loop")
                .to("direct:po-cycle-closeout")
                .to("tack:thought-exit?name=po-cycle")
                .to("direct:po-product-owner-root-exit");

        from("direct:po-product-owner-root-enter")
                .routeId("po-product-owner-root-enter")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Durable product anchors: product documentation pages, current-work page, Plane project,
                        source repository, and team chat.
                        """))
                .to("tack:thought-enter?name=product-owner-routine&maxTokens=500");

        from("direct:po-product-owner-root-exit")
                .routeId("po-product-owner-root-exit")
                .to("tack:thought-exit?name=product-owner-routine");

        from("direct:po-project-basics-loop")
                .routeId("po-project-basics-loop")
                .setProperty("productOwnerProjectBasicsCycle", constant(0))
                .setProperty("decision.product-owner-has-project-basics.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.product-owner-has-project-basics.result").isNotEqualTo(true))
                    .setProperty("productOwnerProjectBasicsCycle", simple("${exchangeProperty.productOwnerProjectBasicsCycle++}"))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Current product-basics read: documentation repository, current-work page, Plane project, source repository,
                        team chat, product navigation. Promote durable anchors to product-owner-routine before basics exits.
                        """))
                    .to("tack:thought-enter?name=po-basics&maxTokens=300")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Verify product project basics without mutation, including:
                                    Git organization agent-workspace, team chat, Plane project, source repository under
                                    agent-workspace
                                    """))
                    .to("tack:act?id=po-workspace-basics-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Are project basics present and linked enough to do product work?
                                    Git organization agent-workspace, team chat, Plane project, source repository under
                                    agent-workspace
                                    """))
                    .to("tack:decide?id=product-owner-has-project-basics")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Read the last 20 messages in the default or project Mattermost channel.
                                    If PM/setup discussion exists, respond there only when a specific product/setup
                                    answer is useful. Include the documentation index link if known.
                                    Do not start product work.
                                    """))
                    .to("tack:act?id=po-project-basics-communication&toolProfile=" + TeamChatToolProfile.ID)
                    .to("tack:thought-exit?name=po-basics")
                .end();

        from("direct:po-team-chat-sync")
                .routeId("po-team-chat-sync")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Read the last 20 project Mattermost messages from the project-basics link. Reply first to a direct
                                product, scope, priority, or acceptance question. If a clarification is needed,
                                ask at most one named question. Do not post generic status or duplicate documentation content.
                                """))
                .to("tack:act?id=po-team-chat-sync&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:po-backlog-selection-loop")
                .routeId("po-backlog-selection-loop")
                .setProperty("poBacklogCycle", constant(0))
                .setProperty("decision.po-backlog-selection-done.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.po-backlog-selection-done.result").isNotEqualTo(true))
                    .setProperty("poBacklogCycle", simple("${exchangeProperty.poBacklogCycle++}"))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Current item selection: Plane work item id/link, owner, priority, status, canonical page.
                        Promote selected item to po-cycle before backlog exits.
                        """))
                    .to("tack:thought-enter?name=po-backlog&maxTokens=250")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Review exactly one PO-owned product/backlog work item needing scope or acceptance.
                                    Do not select developer implementation tasks. Capture priority, status, owner,
                                    and canonical documentation page.
                                    """))
                    .to("tack:act?id=po-backlog-review&toolProfile=" + ProjectManagementToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Read only the documentation index, current-work, the selected item page, and direct product links.
                                    Report missing or stale navigation instead of reading the whole documentation repository.
                                    """))
                    .to("tack:act?id=po-product-context-read&toolProfile=" + SourceControlToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is one product item selected with enough Plane and documentation context to refine it,
                                    or is the missing context clearly identified?
                                    """))
                    .to("tack:decide?id=po-backlog-selection-done")
                    .to("tack:thought-exit?name=po-backlog")
                .end();

        from("direct:po-acceptance-loop")
                .routeId("po-acceptance-loop")
                .setProperty("poAcceptanceCycle", constant(0))
                .setProperty("decision.po-acceptance-done.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.po-acceptance-done.result").isNotEqualTo(true))
                    .setProperty("poAcceptanceCycle", simple("${exchangeProperty.poAcceptanceCycle++}"))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Scope and acceptance work for selected item: goal, users, in/out of scope, acceptance criteria, decisions, open questions. Promote acceptance summary/page link to po-cycle.
                        """))
                    .to("tack:thought-enter?name=po-acceptance&maxTokens=250")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Update exactly one canonical documentation page for the selected item.
                                    Include Plane link, owner, goal, users, in/out of scope, compact Gherkin acceptance criteria,
                                    and either one product decision or one open question. Keep it small and linked from current-work
                                    and the product index. Do not fan out to other pages unless needed for navigation.
                                    """))
                    .to("tack:act?id=po-canonical-acceptance-update&toolProfile=" + SourceControlToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Are scope and acceptance criteria usable, linked, and small enough for PM breakdown?
                                    """))
                    .to("tack:decide?id=po-acceptance-done")
                    .to("tack:thought-exit?name=po-acceptance")
                .end();

        from("direct:po-priority-status-loop")
                .routeId("po-priority-status-loop")
                .setProperty("poPriorityCycle", constant(0))
                .setProperty("decision.po-priority-status-done.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.po-priority-status-done.result").isNotEqualTo(true))
                    .setProperty("poPriorityCycle", simple("${exchangeProperty.poPriorityCycle++}"))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Priority/status handoff: Plane work item status, priority, readiness for PM breakdown, requested PM action.
                        """))
                    .to("tack:thought-enter?name=po-priority&maxTokens=250")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Update priority or product readiness on exactly one Plane work item after verifying current status.
                                    If acceptance is ready, move or annotate it for PM breakdown only if that workflow state exists.
                                    Add one compact PM handoff note with canonical page, priority, acceptance summary, remaining
                                    questions, and requested PM action. Do not create, assign, or start developer tasks.
                                    """))
                    .to("tack:act?id=po-plane-handoff-update&toolProfile=" + ProjectManagementToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is the product item ready for PM handoff, with priority/readiness in Plane
                                    and the canonical documentation page linked?
                                    """))
                    .to("tack:decide?id=po-priority-status-done")
                    .to("tack:thought-exit?name=po-priority")
                .end();

        from("direct:po-cycle-closeout")
                .routeId("po-cycle-closeout")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Read the last 20 messages in the project Mattermost channel from project basics.
                                Post only if the canonical item page changed materially or a teammate is waiting.
                                Include the canonical documentation page and who it unblocks; otherwise stay quiet.
                                """))
                .to("tack:act?id=po-team-chat-closeout&toolProfile=" + TeamChatToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                                If needed, add one small follow-up note to the canonical documentation page.
                                Preserve navigation: current-work, product index, Plane work item, and decisions links.
                                Capture durable product outcomes from Mattermost; leave ephemeral discussion in Mattermost.
                                """))
                .to("tack:act?id=po-follow-up-note&toolProfile=" + SourceControlToolProfile.ID);
    }
}
