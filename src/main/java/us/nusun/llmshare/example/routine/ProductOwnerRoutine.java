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
                        Complete Product Owner cycle, including the focused acceptance-refinement and priority-handoff
                        behaviors when they become applicable. Own the canonical product backlog and translate mission outcomes
                        into prioritized, small user-facing product slices with clear value, scope, testable Gherkin acceptance,
                        product decisions, and readiness. Maintain enough PRODUCT READY work to expose meaningful delivery
                        opportunities and evaluate completed outcomes against product intent.
                        """, "direct:example-software-team-product-owner")
                .subroutine(
                        "acceptance-refinement",
                        "Refine one product backlog item into a small, clearly scoped product slice with testable Gherkin acceptance criteria.",
                        "direct:example-software-team-product-owner-acceptance-refinement")
                .subroutine(
                        "priority-handoff",
                        "Make one product backlog slice delivery-ready with clear priority, Gherkin acceptance context, and readiness evidence.",
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
                                Current product pass: selected product work item, canonical item page, Gherkin acceptance status,
                                priority/readiness, product-ready handoff, and next product action.
                                """))
                            .to("tack:thought-enter?name=po-cycle&maxTokens=300")
                            .to("direct:po-team-chat-sync")
                            .to("direct:po-backlog-selection-loop")
                            .to("direct:po-acceptance-loop")
                            .to("direct:po-priority-status-loop")
                            .to("direct:po-cycle-closeout")
                            .setHeader(TackHeaders.PROMPT, constant("""
                                            Is this product pass locally complete because the canonical backlog represents
                                            the mission's major product outcomes and contains at least one small vertical slice
                                            marked PRODUCT READY with testable Gherkin acceptance, or because one exact product
                                            blocker or user decision was recorded? Do not wait for downstream delivery work to
                                            consume the ready product item. If more product work remains, the next cycle must
                                            choose a distinct unfinished product outcome rather than refining the same ready item again.
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
                            Focused product acceptance pass: selected product item, canonical page, scope, users,
                            Gherkin acceptance criteria, decisions, and open questions.
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
                            Focused product readiness pass: selected product item, priority, readiness state,
                            requested next delivery action, Gherkin acceptance, and canonical page.
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
                        Durable product anchors: canonical product backlog, product documentation pages, current-work page,
                        project-management workspace, source repository, and team chat.
                        """))
                .to("tack:thought-enter?name=product-owner-routine&maxTokens=500");

        from("direct:po-product-owner-root-exit")
                .routeId("po-product-owner-root-exit")
                .to("tack:thought-exit?name=product-owner-routine");

        from("direct:po-project-basics-loop")
                .routeId("po-project-basics-loop")
                .setProperty("decision.product-owner-has-project-basics.result", constant(false))
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Current product-basics read: documentation repository, current-work page,
                        project-management workspace, source repository, team chat, and product navigation.
                        Promote durable anchors to product-owner-routine before basics exits.
                        """))
                .to("tack:thought-enter?name=po-basics&maxTokens=300")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Verify product project basics once without mutation using the capabilities available to you.
                                Product work may begin only when there is a usable project-management workspace, a source
                                repository under agent-workspace, a documentation repository with usable index/current-work
                                navigation, and usable team chat/project-basics navigation. A static active-roster snapshot
                                is not a basic. Do not repeatedly reread the workspace and do not begin product work when
                                any basic is missing.
                                """))
                .to("tack:act?id=po-workspace-basics-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                                Are project basics present and linked enough to do product work: project management,
                                source repository under agent-workspace, documentation repository/navigation, and team chat?
                                """))
                .to("tack:decide?id=product-owner-has-project-basics")
                .to("tack:thought-exit?name=po-basics");

        from("direct:po-missing-basics-closeout")
                .routeId("po-missing-basics-closeout")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Project basics are not usable for product work. Read recent team chat only if needed.
                                Reply once to an existing setup thread when a concise product or setup answer helps;
                                otherwise stay quiet. Do not repost setup requests and do not begin product work without
                                project management, source repository, documentation repository, and usable navigation.
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
                        Current item selection: project-management work item id/link, owner, priority, status,
                        and canonical page. Promote the selected item to po-cycle before backlog exits.
                        """))
                    .to("tack:thought-enter?name=po-backlog&maxTokens=250")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Use project management to inspect and maintain the canonical product backlog.
                                    The backlog is the required origin of new product work: every mission outcome that needs
                                    delivery must first be represented here as a product item. Select one small unfinished
                                    user-facing product slice that needs product ownership. When no suitable unfinished slice
                                    exists, create one from an unrepresented mission outcome and link it to the mission container
                                    when available. Maintain enough small product slices to expose the next meaningful delivery
                                    opportunities. Keep implementation tasks separate from product backlog items. Do not reselect
                                    PRODUCT READY work unless product input was requested or acceptance became stale.
                                    """))
                    .to("tack:act?id=po-backlog-review&toolProfile=" + ProjectManagementToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Read only the documentation index, current-work, the selected item page, and direct product links.
                                    Report missing or stale navigation instead of reading the whole documentation repository.
                                    """))
                    .to("tack:act?id=po-product-context-read&toolProfile=" + SourceControlToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is exactly one unfinished product slice selected or created for product work, with
                                    project-management and documentation context captured, or is the missing context clearly identified?
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
                        Scope and acceptance work for selected item: goal, users, in/out of scope, Gherkin acceptance,
                        decisions, and open questions. Promote acceptance summary/page link to po-cycle.
                        """))
                    .to("tack:thought-enter?name=po-acceptance&maxTokens=250")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Curate exactly one compact product page for the selected product slice.
                                    Include the project-management item link, user value, scope boundaries, and compact testable
                                    Gherkin scenarios using Given/When/Then for observable acceptance behavior. Include one product
                                    decision or open question. Keep the page product-focused and avoid implementation decomposition,
                                    technical sequencing, or code-level instructions; those belong to downstream delivery planning.
                                    Keep pages linked and small; split or summarize before appending long logs.
                                    """))
                    .to("tack:act?id=po-canonical-acceptance-update&toolProfile=" + SourceControlToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Are scope boundaries and testable Gherkin acceptance scenarios usable, linked, and small enough
                                    for downstream delivery planning? Return false when acceptance is only prose or is too ambiguous
                                    to determine whether the product outcome was satisfied.
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
                        Priority/readiness handoff: project-management work item status, priority,
                        delivery readiness, Gherkin acceptance, and requested next delivery action.
                        """))
                    .to("tack:thought-enter?name=po-priority&maxTokens=250")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Using project management, keep the selected product slice in Backlog and update its
                                    priority/readiness after verifying status. Mark it PRODUCT READY only when its canonical page
                                    contains usable scope and testable Gherkin acceptance. Add or refresh a comment beginning exactly
                                    PRODUCT READY with canonical page, priority, summary, open questions, and requested next delivery
                                    action. When reviewing finished work, accept or move only genuinely complete product outcomes to Done.
                                    Keep this routine focused on product artifacts and do not create, assign, sequence, or start
                                    implementation tasks.
                                    """))
                    .to("tack:act?id=po-plane-handoff-update&toolProfile=" + ProjectManagementToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is the selected product slice delivery-ready with priority, canonical page, testable Gherkin
                                    acceptance, and an exact PRODUCT READY comment, or has one exact product blocker or question been recorded?
                                    """))
                    .to("tack:decide?id=po-priority-status-done")
                    .to("tack:thought-exit?name=po-priority")
                .end();

        from("direct:po-cycle-closeout")
                .routeId("po-cycle-closeout")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Read recent project chat and continue useful product conversations.
                                Reply when a teammate needs scope, acceptance, or product judgment; otherwise ask nothing.
                                Link the project-management item or product page when it helps the conversation.
                                """))
                .to("tack:act?id=po-team-chat-closeout&toolProfile=" + TeamChatToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                                Make one small documentation curation improvement if it clarifies the product.
                                Preserve navigation and durable decisions; leave day-to-day work tracking in project management.
                                """))
                .to("tack:act?id=po-follow-up-note&toolProfile=" + SourceControlToolProfile.ID);
    }
}
