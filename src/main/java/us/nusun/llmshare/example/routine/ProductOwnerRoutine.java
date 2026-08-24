package us.nusun.llmshare.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.framework.inventory.RoutineProvider;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.tack.TackHeaders;

import java.util.List;

import us.nusun.llmshare.example.tool.ProjectManagementToolProfile;
import us.nusun.llmshare.example.tool.SourceControlToolProfile;
import us.nusun.llmshare.example.tool.SourceControlWriteToolProfile;
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
                        Own product definition and acceptance for the whole mission through completion. Maintain the canonical
                        product backlog, translate mission outcomes into prioritized small user-facing slices with clear value,
                        scope and testable Gherkin acceptance, keep useful PRODUCT READY work available, and evaluate
                        PRODUCT ACCEPTANCE READY outcomes against product intent while the mission still has unfinished outcomes.
                        """, "direct:example-software-team-product-owner")
                .subroutine(
                        "acceptance-refinement",
                        "Refine one product backlog item into a small, clearly scoped product slice with testable Gherkin acceptance criteria.",
                        "direct:example-software-team-product-owner-acceptance-refinement")
                .subroutine(
                        "priority-handoff",
                        "Make one product backlog slice delivery-ready with clear priority, Gherkin acceptance context, and readiness evidence, or accept a completed product outcome against that acceptance.",
                        "direct:example-software-team-product-owner-priority-handoff")
                .defaultLane("high-pass")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-software-team-product-owner")
                .routeId("example-software-team-product-owner")
                .to("direct:po-product-owner-root-enter")
                .setProperty("decision.product-owner-mission-complete.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.product-owner-mission-complete.result").isNotEqualTo(true))
                    .to("direct:po-project-basics-loop")
                    .choice()
                        .when(exchangeProperty("decision.product-owner-has-project-basics.result").isEqualTo(true))
                            .setProperty("productOwnerCycle", constant(0))
                            .setProperty("decision.product-owner-done.result", constant(false))
                            .loopDoWhile(exchangeProperty("decision.product-owner-done.result").isNotEqualTo(true))
                                .setProperty("productOwnerCycle", simple("${exchangeProperty.productOwnerCycle++}"))
                                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                                    Current product pass: selected product work item, canonical item page, Gherkin acceptance status,
                                    completion evidence when present, priority/readiness, product-ready handoff, and next product action.
                                    """))
                                .to("tack:thought-enter?name=po-cycle&maxTokens=300")
                                .to("direct:po-team-chat-sync")
                                .to("direct:po-backlog-selection-loop")
                                .to("direct:po-acceptance-loop")
                                .to("direct:po-priority-status-loop")
                                .to("direct:po-cycle-closeout")
                                .setHeader(TackHeaders.PROMPT, constant("""
                                                Is this product pass locally complete because a PRODUCT ACCEPTANCE READY outcome was
                                                accepted or returned with one exact unmet Gherkin outcome; the canonical backlog represents
                                                the mission's major product outcomes and contains at least one small vertical slice marked
                                                PRODUCT READY with testable Gherkin acceptance; or one exact product blocker or user decision
                                                was recorded? Do not wait for downstream delivery work to consume product output. If more
                                                product work remains, choose a distinct unfinished outcome rather than repeatedly refining
                                                the same unchanged ready item.
                                                """))
                                .to("tack:decide?id=product-owner-done")
                                .to("tack:thought-exit?name=po-cycle")
                            .end()
                        .endChoice()
                        .otherwise()
                            .to("direct:po-missing-basics-closeout")
                    .end()
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is the entire mission complete from the product-ownership perspective?
                                    Return true only when durable current-mission evidence shows every required user-facing outcome
                                    has been accepted against its intended acceptance criteria and no product decision, acceptance,
                                    backlog, or follow-up outcome remains. Return false when project basics are missing, product work
                                    is merely ready for delivery, implementation is still pending, acceptance is unresolved, or a local
                                    product cycle simply has no immediate action.
                                    """))
                    .to("tack:decide?id=product-owner-mission-complete")
                    .filter(exchangeProperty("decision.product-owner-mission-complete.result").isNotEqualTo(true))
                        .delay(120000)
                    .end()
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
                            Gherkin acceptance criteria, completion evidence when present, decisions, and open questions.
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
                            requested next delivery action, Gherkin acceptance, completion evidence, and canonical page.
                            """))
                        .to("tack:thought-enter?name=po-cycle&maxTokens=300")
                        .to("direct:po-team-chat-sync")
                        .to("direct:po-backlog-selection-loop")
                        .to("direct:po-acceptance-loop")
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
                        readiness/acceptance handoff, and canonical page. Promote the selected item to po-cycle before backlog exits.
                        """))
                    .to("tack:thought-enter?name=po-backlog&maxTokens=250")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Use project management to inspect and maintain the canonical product backlog. First select one
                                    product slice carrying PRODUCT ACCEPTANCE READY so a completed outcome does not wait behind new
                                    shaping work. Otherwise select one small unfinished user-facing product slice needing product
                                    ownership. When no suitable unfinished slice exists, create one from an unrepresented mission
                                    outcome and link it to the mission container when available. Maintain enough small product slices
                                    to expose meaningful delivery opportunities. Keep implementation tasks separate from product items.
                                    Do not reselect unchanged PRODUCT READY work unless PRODUCT ACCEPTANCE READY, product input was
                                    requested, or acceptance became stale.
                                    """))
                    .to("tack:act?id=po-backlog-review&toolProfile=" + ProjectManagementToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Read only the documentation index, current-work, selected item page, direct product links,
                                    and completion evidence linked by PRODUCT ACCEPTANCE READY when present. Report missing or stale
                                    navigation instead of reading the whole documentation repository.
                                    """))
                    .to("tack:act?id=po-product-context-read&toolProfile=" + SourceControlToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is exactly one product slice selected for either acceptance of a PRODUCT ACCEPTANCE READY outcome
                                    or unfinished product work, with project-management and documentation context captured, or is the
                                    missing context clearly identified?
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
                        PRODUCT ACCEPTANCE READY completion evidence when present, decisions, and open questions.
                        Promote acceptance evaluation/page link to po-cycle.
                        """))
                    .to("tack:thought-enter?name=po-acceptance&maxTokens=250")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Curate or evaluate exactly one compact canonical product page for the selected product slice.
                                    For unfinished product work, include the project-management item link, user value, scope boundaries,
                                    and compact testable Gherkin scenarios using Given/When/Then for observable acceptance behavior,
                                    plus one product decision or open question. Do not add implementation decomposition or code-level
                                    instructions. For PRODUCT ACCEPTANCE READY work, preserve the existing scope and Gherkin criteria;
                                    inspect only the linked completion evidence and record which existing scenarios are satisfied or
                                    which exact scenario remains unmet. Never rewrite acceptance criteria to fit the implementation.
                                    Keep pages linked and small.
                                    """))
                    .to("tack:act?id=po-canonical-acceptance-update&toolProfile=" + SourceControlWriteToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    If this is PRODUCT ACCEPTANCE READY, has each existing Gherkin scenario been evaluated against
                                    durable completion evidence with either satisfaction or one exact unmet outcome recorded?
                                    Otherwise, are scope boundaries and testable Gherkin acceptance scenarios usable, linked, and
                                    small enough for downstream delivery planning? Return false for ambiguous prose-only acceptance.
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
                        Product readiness/acceptance handoff: work-item status, priority, Gherkin acceptance,
                        PRODUCT READY or PRODUCT ACCEPTANCE READY evidence, and requested next delivery action.
                        """))
                    .to("tack:thought-enter?name=po-priority&maxTokens=250")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Using project management, verify the selected product slice and its canonical acceptance page.
                                    For unfinished work, keep it in Backlog and mark it PRODUCT READY only when scope and testable
                                    Gherkin acceptance are usable; include canonical page, priority, summary, open questions, and
                                    requested next delivery action. For PRODUCT ACCEPTANCE READY work, move the product slice to Done
                                    only when durable evidence satisfies the existing Gherkin scenarios. If one or more scenarios are
                                    unmet, keep the product slice active and add or refresh PRODUCT READY with the exact unmet acceptance
                                    outcome and requested next delivery action so delivery management can bridge the gap. Do not create,
                                    assign, sequence, or start implementation tasks.
                                    """))
                    .to("tack:act?id=po-plane-handoff-update&toolProfile=" + ProjectManagementToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is this product action complete because the slice was accepted Done against its existing Gherkin;
                                    was returned PRODUCT READY with one exact unmet acceptance outcome; is delivery-ready with priority,
                                    canonical page, testable Gherkin acceptance, and PRODUCT READY; or has one exact product blocker or
                                    question been recorded?
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
                .to("tack:act?id=po-follow-up-note&toolProfile=" + SourceControlWriteToolProfile.ID);
    }
}
