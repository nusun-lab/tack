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
                        shape Backlog product work, refine scope and acceptance, hand ready slices to PM, accept finished outcomes,
                        keep product documentation useful, and talk with teammates when interaction improves the work.
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
                .choice()
                    .when(exchangeProperty("decision.product-owner-has-project-basics.result").isEqualTo(true))
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
                                            Is your PO pass locally complete because the thin product pipeline exists
                                            with at least one small vertical slice marked PM HANDOFF: READY and major
                                            mission outcomes represented or explicitly deferred, or because one exact
                                            product blocker/user decision was recorded?
                                            Do not wait for PM or DEV to consume the handoff.
                                            If more PO work remains, the next cycle must choose a distinct unfinished
                                            product outcome rather than refining the same handed-off item again.
                                            """))
                            .to("tack:decide?id=product-owner-done")
                            .to("tack:thought-exit?name=po-cycle")
                        .end()
                    .endChoice()
                    .otherwise()
                        .to("direct:po-missing-basics-closeout")
                .end()
                .to("direct:po-product-owner-root-exit");

        from("direct:example-software-team-product-owner-acceptance-refinement")
                .routeId("example-software-team-product-owner-acceptance-refinement")
                .to("direct:po-product-owner-root-enter")
                .to("direct:po-project-basics-loop")
                .choice()
                    .when(exchangeProperty("decision.product-owner-has-project-basics.result").isEqualTo(true))
                        .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                            Focused PO acceptance pass: selected product item, canonical page, scope, users, acceptance criteria, decisions, and open questions.
                            """))
                        .to("tack:thought-enter?name=po-cycle&maxTokens=300")
                        .to("direct:po-team-chat-sync")
                        .to("direct:po-backlog-selection-loop")
                        .to("direct:po-acceptance-loop")
                        .to("direct:po-cycle-closeout")
                        .to("tack:thought-exit?name=po-cycle")
                    .otherwise()
                        .to("direct:po-missing-basics-closeout")
                .end()
                .to("direct:po-product-owner-root-exit");

        from("direct:example-software-team-product-owner-priority-handoff")
                .routeId("example-software-team-product-owner-priority-handoff")
                .to("direct:po-product-owner-root-enter")
                .to("direct:po-project-basics-loop")
                .choice()
                    .when(exchangeProperty("decision.product-owner-has-project-basics.result").isEqualTo(true))
                        .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                            Focused PO handoff pass: selected product item, priority, readiness, PM request, Plane state, and canonical page.
                            """))
                        .to("tack:thought-enter?name=po-cycle&maxTokens=300")
                        .to("direct:po-team-chat-sync")
                        .to("direct:po-backlog-selection-loop")
                        .to("direct:po-priority-status-loop")
                        .to("direct:po-cycle-closeout")
                        .to("tack:thought-exit?name=po-cycle")
                    .otherwise()
                        .to("direct:po-missing-basics-closeout")
                .end()
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
                .setProperty("decision.product-owner-has-project-basics.result", constant(false))
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Current product-basics read: documentation repository, current-work page, Plane project, source repository,
                        team chat, product navigation. Promote durable anchors to product-owner-routine before basics exits.
                        """))
                .to("tack:thought-enter?name=po-basics&maxTokens=300")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Verify product project basics once without mutation.
                                Product work may begin only when there is a Plane project, source repository under
                                agent-workspace, documentation repository with usable index/current-work navigation,
                                and a usable team chat/project-basics link. A static active-roster snapshot is not a basic.
                                Do not repeatedly reread the workspace and do not begin product work when any basic is missing.
                                """))
                .to("tack:act?id=po-workspace-basics-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                                Are project basics present and linked enough to do product work: Plane project,
                                source repository under agent-workspace, documentation repository, and usable navigation/chat?
                                """))
                .to("tack:decide?id=product-owner-has-project-basics")
                .to("tack:thought-exit?name=po-basics");

        from("direct:po-missing-basics-closeout")
                .routeId("po-missing-basics-closeout")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Project basics are not usable for product work. Read recent Mattermost only if needed.
                                Reply once to an existing PM/setup thread when a concise product/setup answer helps;
                                otherwise stay quiet. Do not repost setup requests and do not begin product work without
                                Plane, source repository, documentation repository, and usable navigation.
                                The director covers missing PM staffing.
                                """))
                .to("tack:act?id=po-missing-basics-closeout&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:po-team-chat-sync")
                .routeId("po-team-chat-sync")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Read recent project chat and join real product conversations.
                                Answer direct scope or acceptance questions, or ask one named question that improves the backlog.
                                Avoid generic status and duplicated documentation.
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
                                    Work only in the Plane Backlog when creating or refining product work.
                                    Select one small user-facing slice needing PO attention; ignore implementation tasks and To Do work.
                                    If none exists, create one Backlog slice linked to the mission container when useful.
                                    Do not reselect PM-ready work unless PO input was requested or acceptance became stale.
                                    """))
                    .to("tack:act?id=po-backlog-review&toolProfile=" + ProjectManagementToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Read only the documentation index, current-work, the selected item page, and direct product links.
                                    Report missing or stale navigation instead of reading the whole documentation repository.
                                    """))
                    .to("tack:act?id=po-product-context-read&toolProfile=" + SourceControlToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is exactly one unfinished product slice selected or created for PO work, with Plane and
                                    documentation context captured, or is the missing context clearly identified?
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
                                    Curate exactly one compact product page for the selected Backlog slice.
                                    Include Plane link, user value, scope boundaries, acceptance criteria, and one decision or open question.
                                    Keep pages product-focused, linked, and small; split or summarize before appending long logs.
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
                                    Keep the selected product slice in Backlog and update its priority/readiness after verifying status.
                                    When acceptance is usable, add or refresh PM HANDOFF: READY with page, priority, summary, questions, and PM request.
                                    When reviewing finished work, accept or move only genuinely complete product outcomes to Done.
                                    Never create, assign, or start developer tasks; PM moves ready work to To Do.
                                    """))
                    .to("tack:act?id=po-plane-handoff-update&toolProfile=" + ProjectManagementToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is the selected product slice ready for PM handoff with priority, canonical page,
                                    and an exact PM HANDOFF: READY comment, or has one exact product blocker/question been recorded?
                                    """))
                    .to("tack:decide?id=po-priority-status-done")
                    .to("tack:thought-exit?name=po-priority")
                .end();

        from("direct:po-cycle-closeout")
                .routeId("po-cycle-closeout")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Read recent project chat and continue useful product conversations.
                                Reply when a teammate needs scope, acceptance, or product judgment; otherwise ask nothing.
                                Link the Plane item or product page when it helps the conversation.
                                """))
                .to("tack:act?id=po-team-chat-closeout&toolProfile=" + TeamChatToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                                Make one small documentation curation improvement if it clarifies the product.
                                Preserve navigation and durable decisions; leave day-to-day work tracking in Plane.
                                """))
                .to("tack:act?id=po-follow-up-note&toolProfile=" + SourceControlToolProfile.ID);
    }
}
