package us.nusun.llmshare.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import us.nusun.llmshare.example.tool.ProjectManagementToolProfile;
import us.nusun.llmshare.example.tool.SourceControlWriteToolProfile;
import us.nusun.llmshare.example.tool.TeamChatToolProfile;
import us.nusun.llmshare.example.tool.WorkspaceReadonlyToolProfile;
import us.nusun.llmshare.framework.inventory.RoutineProvider;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.tack.TackHeaders;

import java.util.List;

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
                        Own product definition and acceptance for the whole mission through completion. Decompose mission
                        outcomes into prioritized small user-facing product slices, maintain their canonical Gherkin acceptance,
                        answer explicit product questions returned by delivery, move delivery-ready product slices from Backlog
                        to Todo, and accept completed In Progress product outcomes only when durable evidence satisfies their
                        current acceptance contract. Product slices describe user outcomes; implementation decomposition belongs
                        to the Project Manager.
                        """, "direct:example-software-team-product-owner")
                .subroutine("acceptance-refinement", "Refine one Backlog product slice or answer one returned product question with clear scope and testable Gherkin acceptance.", "direct:example-software-team-product-owner-acceptance-refinement")
                .subroutine("priority-handoff", "Move one ready product slice Backlog -> Todo, answer one returned product question, or accept/reject one completed In Progress product outcome.", "direct:example-software-team-product-owner-priority-handoff")
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
                    .to("direct:po-orientation")
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Choose exactly one highest-priority Product Owner focus for the next focused pass.
                            Allowed focus values, in priority order when genuinely actionable:
                            - missing-basics: the shared Plane/source/docs/chat foundation is not usable for product work.
                            - product-work: there is an unresolved PRODUCT QUESTION, a current product acceptance candidate, a Backlog product slice needing definition/handoff, an unrepresented required user-facing outcome, or the delivery-ready Todo product backlog is too shallow. Stay in product work long enough to build a modest useful backlog, one product slice per act, without inventing speculative scope.
                            - coordination: a fresh team conversation needs Product Owner judgment or clarification.
                            - closeout: a concrete product documentation/chat curation follow-up is useful after durable product work.
                            - completion: durable evidence makes mission completion plausible enough for the dedicated completion check.
                            - wait: none of the above is useful now; avoid generic status or repeated no-op traversal.
                            Plane real states remain authoritative lifecycle state. Preserve current PRODUCT QUESTION/HANDOFF and acceptance-lease behavior on this branch. Return only the focus object.
                            """))
                    .to("tack:create?id=po-priority-focus&schemaId=product-owner-priority-focus&schema=classpath:schemas/example/software-team/product-owner-priority-focus-schema.json")
                    .unmarshal().json(JsonLibrary.Jackson, PriorityFocus.class)
                    .process(exchange -> exchange.setProperty("poPriorityFocus", exchange.getMessage().getBody(PriorityFocus.class).focus()))
                    .choice()
                        .when(exchangeProperty("poPriorityFocus").isEqualTo("missing-basics"))
                            .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Focused missing-foundation response: communicate only when useful and do not begin product work."))
                            .to("tack:thought-enter?name=po-missing-basics-focus&maxTokens=250")
                            .to("direct:po-missing-basics-closeout")
                            .to("tack:thought-exit?name=po-missing-basics-focus")
                        .when(exchangeProperty("poPriorityFocus").isEqualTo("product-work"))
                            .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Focused product work: resolve high-priority product returns/acceptance, then define and hand off product slices one at a time until a modest delivery-ready backlog exists or no useful product work remains."))
                            .to("tack:thought-enter?name=po-product-work-focus&maxTokens=400")
                            .to("direct:po-product-work-focus")
                            .to("tack:thought-exit?name=po-product-work-focus")
                        .when(exchangeProperty("poPriorityFocus").isEqualTo("coordination"))
                            .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Focused Product Owner coordination for one fresh scope, acceptance, or product-judgment exchange."))
                            .to("tack:thought-enter?name=po-coordination-focus&maxTokens=250")
                            .to("direct:po-team-chat-sync")
                            .to("tack:thought-exit?name=po-coordination-focus")
                        .when(exchangeProperty("poPriorityFocus").isEqualTo("closeout"))
                            .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Focused product closeout curation when a durable conversation or documentation improvement is concretely useful."))
                            .to("tack:thought-enter?name=po-closeout-focus&maxTokens=250")
                            .to("direct:po-cycle-closeout")
                            .to("tack:thought-exit?name=po-closeout-focus")
                        .when(exchangeProperty("poPriorityFocus").isEqualTo("completion"))
                            .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Focused Product Owner mission completion evaluation using minimal authoritative evidence."))
                            .to("tack:thought-enter?name=po-completion-focus&maxTokens=300")
                            .to("direct:po-completion-focus")
                            .to("tack:thought-exit?name=po-completion-focus")
                        .otherwise()
                            .delay(120000)
                    .end()
                .end()
                .end()
                .to("direct:po-product-owner-root-exit");

        from("direct:po-orientation")
                .routeId("po-orientation")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read the smallest fresh current-mission slice needed to rank the Product Owner's next focus. Determine
                        only: whether shared project basics are usable; whether there is an unresolved PRODUCT QUESTION, current
                        acceptance candidate, Backlog slice needing definition, or genuinely unrepresented user outcome; whether
                        the delivery-ready Todo product backlog appears shallow; whether one fresh team exchange needs product
                        judgment; whether one concrete closeout curation need exists; and whether mission completion is plausible.
                        Do not mutate and do not perform the selected product work here. Stop when routing signals are clear.
                        """))
                .to("tack:act?id=po-orientation&toolProfile=" + WorkspaceReadonlyToolProfile.ID);

        from("direct:po-product-work-focus")
                .routeId("po-product-work-focus")
                .setProperty("poFocusedProductIterations", constant(0))
                .setProperty("poFocusedProductContinue", constant(true))
                .setProperty("decision.po-product-backlog-deep-enough.result", constant(false))
                .loopDoWhile(simple("${exchangeProperty.poFocusedProductContinue} == true && ${exchangeProperty.poFocusedProductIterations} < 6"))
                    .setProperty("poFocusedProductIterations", simple("${exchangeProperty.poFocusedProductIterations++}"))
                    .setProperty("poFocusedProductContinue", constant(false))
                    .to("direct:po-select-product-work")
                    .choice()
                        .when(exchangeProperty("decision.product-owner-has-work.result").isEqualTo(true))
                            .to("direct:po-acceptance-work")
                            .to("direct:po-plane-state-handoff")
                            .choice()
                                .when(exchangeProperty("act.po-canonical-acceptance-update.durableProgress").isEqualTo(true))
                                    .setProperty("poFocusedProductContinue", constant(true))
                            .end()
                            .choice()
                                .when(exchangeProperty("act.po-plane-handoff-update.durableProgress").isEqualTo(true))
                                    .setProperty("poFocusedProductContinue", constant(true))
                            .end()
                    .end()
                    .to("direct:po-product-backlog-depth-check")
                    .choice()
                        .when(exchangeProperty("decision.po-product-backlog-deep-enough.result").isEqualTo(true))
                            .setProperty("poFocusedProductContinue", constant(false))
                    .end()
                .end();

        from("direct:po-product-backlog-depth-check")
                .routeId("po-product-backlog-depth-check")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read only the current product-parent backlog in Plane. Count delivery-ready Todo product slices with usable
                        canonical acceptance, and note whether unresolved PRODUCT QUESTION or current acceptance-candidate work remains.
                        Do not mutate or inspect implementation details.
                        """))
                .to("tack:act?id=po-product-backlog-depth-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Is the product backlog deep enough for Project Management to maintain useful delivery planning without
                        speculative product invention? Return false whenever a current PRODUCT QUESTION or acceptance candidate still
                        requires Product Owner work. Otherwise return true when several usable Todo product slices are ready, normally
                        about 3-5, or when the mission has no additional safely definable user-facing slice right now.
                        """))
                .to("tack:decide?id=po-product-backlog-deep-enough");

        from("direct:po-completion-focus")
                .routeId("po-completion-focus")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Review current mission completion status once using the smallest authoritative read.
                        One verified unfinished user-facing outcome or unresolved PRODUCT QUESTION is enough to
                        conclude not complete. Plane state is authoritative. Historical handoffs superseded by newer
                        disposition evidence are not current work. Do not mutate anything in this check.
                        """))
                .to("tack:act?id=po-mission-status-review&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Is the entire mission complete from the product-ownership perspective?
                        Return true only when every required user-facing outcome is represented and accepted Done
                        against its current intended acceptance criteria, with no unresolved PRODUCT QUESTION,
                        product decision, stale acceptance lease, or acceptance follow-up. Todo or In Progress
                        product work means the mission is not complete.
                        """))
                .to("tack:decide?id=product-owner-mission-complete");

        from("direct:example-software-team-product-owner-acceptance-refinement")
                .routeId("example-software-team-product-owner-acceptance-refinement")
                .to("direct:po-product-owner-root-enter")
                .to("direct:po-project-basics-check")
                .choice()
                    .when(exchangeProperty("decision.product-owner-has-project-basics.result").isEqualTo(true))
                        .to("direct:po-select-product-work")
                        .choice()
                            .when(exchangeProperty("decision.product-owner-has-work.result").isEqualTo(true))
                                .to("direct:po-acceptance-work")
                        .endChoice()
                        .to("direct:po-cycle-closeout")
                    .otherwise()
                        .to("direct:po-missing-basics-closeout")
                .end()
                .to("direct:po-product-owner-root-exit");

        from("direct:example-software-team-product-owner-priority-handoff")
                .routeId("example-software-team-product-owner-priority-handoff")
                .to("direct:po-product-owner-root-enter")
                .to("direct:po-project-basics-check")
                .choice()
                    .when(exchangeProperty("decision.product-owner-has-project-basics.result").isEqualTo(true))
                        .to("direct:po-select-product-work")
                        .choice()
                            .when(exchangeProperty("decision.product-owner-has-work.result").isEqualTo(true))
                                .to("direct:po-acceptance-work")
                                .to("direct:po-plane-state-handoff")
                        .endChoice()
                        .to("direct:po-cycle-closeout")
                    .otherwise()
                        .to("direct:po-missing-basics-closeout")
                .end()
                .to("direct:po-product-owner-root-exit");

        from("direct:po-product-owner-root-enter")
                .routeId("po-product-owner-root-enter")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Durable product anchors: canonical product backlog, selected product slice and Plane state,
                        returned product questions, acceptance-lease ordering, product documentation, current-work page,
                        project-management workspace, and source repository.
                        """))
                .to("tack:thought-enter?name=product-owner-routine&maxTokens=500");

        from("direct:po-product-owner-root-exit")
                .routeId("po-product-owner-root-exit")
                .to("tack:thought-exit?name=product-owner-routine");

        from("direct:po-project-basics-check")
                .routeId("po-project-basics-check")
                .setProperty("decision.product-owner-has-project-basics.result", constant(false))
                .setHeader(TackHeaders.PROMPT, constant("""
                        Verify product project basics once without mutation. Product work requires a usable Plane
                        workspace, source repository under agent-workspace, documentation repository/navigation,
                        and team chat. One confirmed missing required basic is enough to stop. Do not repeatedly
                        reread the workspace or begin product work when basics are missing.
                        """))
                .to("tack:act?id=po-workspace-basics-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("Are project basics present and linked enough to do product work?"))
                .to("tack:decide?id=product-owner-has-project-basics");

        from("direct:po-missing-basics-closeout")
                .routeId("po-missing-basics-closeout")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Project basics are not usable for product work. Read recent team chat only if needed.
                        Reply once to a relevant setup conversation when useful; otherwise stay quiet. Do not create
                        product work until the shared workspace is usable.
                        """))
                .to("tack:act?id=po-missing-basics-closeout&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:po-product-cycle")
                .routeId("po-product-cycle")
                .to("direct:po-team-chat-sync")
                .setProperty("poProductiveIterations", constant(0))
                .setProperty("poMadeProgress", constant(true))
                .loopDoWhile(simple("${exchangeProperty.poMadeProgress} == true && ${exchangeProperty.poProductiveIterations} < 6"))
                    .setProperty("poProductiveIterations", simple("${exchangeProperty.poProductiveIterations++}"))
                    .setProperty("poMadeProgress", constant(false))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Current product work unit: one freshly selected product slice, its real Plane state, product scope/Gherkin, returned product question, current acceptance lease, delivery or acceptance handoff, and the next distinct product outcome when more work remains."))
                    .to("tack:thought-enter?name=po-cycle&maxTokens=300")
                    .to("direct:po-select-product-work")
                    .choice()
                        .when(exchangeProperty("decision.product-owner-has-work.result").isEqualTo(true))
                            .to("direct:po-acceptance-work")
                            .choice().when(exchangeProperty("act.po-canonical-acceptance-update.durableProgress").isEqualTo(true)).setProperty("poMadeProgress", constant(true)).endChoice()
                            .to("direct:po-plane-state-handoff")
                            .choice().when(exchangeProperty("act.po-plane-handoff-update.durableProgress").isEqualTo(true)).setProperty("poMadeProgress", constant(true)).endChoice()
                    .end()
                    .to("tack:thought-exit?name=po-cycle")
                .end()
                .to("direct:po-cycle-closeout");

        from("direct:po-team-chat-sync")
                .routeId("po-team-chat-sync")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read recent project chat and join real product conversations. Answer direct scope or
                        acceptance questions, or ask one named question that materially improves product definition.
                        Avoid generic status. If no relevant conversation is evident, make no post.
                        """))
                .to("tack:act?id=po-team-chat-sync&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:po-select-product-work")
                .routeId("po-select-product-work")
                .setProperty("decision.product-owner-has-work.result", constant(false))
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Product selection: one product parent only, never an implementation child. Prefer an explicit returned
                        product question, then a current acceptance lease, then one Backlog user outcome needing refinement, then
                        create a missing product outcome only when the mission has an unrepresented user-facing outcome.
                        """))
                .to("tack:thought-enter?name=po-selection&maxTokens=250")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Using fresh Plane evidence, select exactly one PRODUCT PARENT, never an implementation child.

                        First prefer a Todo or In Progress product parent with an unresolved PRODUCT QUESTION from
                        Project Management. A PRODUCT QUESTION is unresolved only when it is newer than the latest
                        Product Owner PRODUCT HANDOFF that explicitly resolves that question. This is the authoritative
                        delivery -> Product Owner return edge for unusable acceptance, product ambiguity, or a product-owned
                        blocker discovered during implementation.

                        Otherwise prefer an In Progress product parent only when its PM PRODUCT ACCEPTANCE READY is a
                        CURRENT LEASE over the current product contract. Current means that READY is newer than every
                        PRODUCT ACCEPTANCE FEEDBACK, PRODUCT QUESTION, and PRODUCT HANDOFF on that parent. Any newer
                        product question or Product Owner handoff invalidates an older READY even when the question has
                        since been resolved; delivery must publish a fresh READY against the clarified contract.

                        Before accepting, also verify every implementation child under the product parent has a terminal
                        delivery disposition: Done, or Cancelled with explicit evidence that it is superseded/non-required
                        and contributes no required acceptance coverage. Do not accept while any required Todo/In Progress
                        child remains, while any Todo/In Progress TOPOLOGY INVESTIGATION affects this product, or while a
                        current TECHNICAL FINDING affecting this product lacks a later PM TOPOLOGY_APPLIED disposition.
                        Cancelled implementation children provide zero delivery coverage and must have superseding required
                        Done coverage unless the corresponding product scope itself was explicitly cancelled.

                        Otherwise select one Backlog product slice needing product definition. If no suitable Backlog
                        slice exists and a required mission user outcome is genuinely unrepresented, create one Backlog
                        product parent.

                        Product parents describe observable user value and acceptance. They are not developer tasks.
                        Do not create implementation children, assign developers, or describe code patches. A Todo or
                        In Progress parent is Product Owner actionable only for a current PRODUCT QUESTION or a current
                        PRODUCT ACCEPTANCE READY lease. Stickiness never justifies inventing additional product scope.
                        """))
                .to("tack:act?id=po-backlog-review&toolProfile=" + ProjectManagementToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Is exactly one current product parent selected for useful Product Owner work now?
                        Return true for a Todo/In Progress parent with an unresolved PRODUCT QUESTION, a Backlog parent
                        needing definition/handoff, or an In Progress parent with a CURRENT PRODUCT ACCEPTANCE READY lease
                        newer than every PRODUCT ACCEPTANCE FEEDBACK, PRODUCT QUESTION, and PRODUCT HANDOFF, with every child
                        terminally dispositioned and no unresolved topology evidence. Return false otherwise.
                        """))
                .to("tack:decide?id=product-owner-has-work")
                .choice()
                    .when(exchangeProperty("decision.product-owner-has-work.result").isEqualTo(true))
                        .setHeader(TackHeaders.PROMPT, constant("""
                                Read only the selected product page, current-work navigation, direct completion evidence,
                                child terminal dispositions, and the selected parent's current PRODUCT QUESTION / PRODUCT HANDOFF /
                                PRODUCT ACCEPTANCE READY / PRODUCT ACCEPTANCE FEEDBACK / TECHNICAL FINDING / TOPOLOGY_APPLIED ordering.
                                Use Plane for handoff ordering and Gitea for canonical product/delivery evidence. Do not read
                                the whole documentation repository.
                                """))
                        .to("tack:act?id=po-product-context-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .end()
                .to("tack:thought-exit?name=po-selection");

        from("direct:po-acceptance-work")
                .routeId("po-acceptance-work")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Selected product parent: user value, in/out scope, existing Gherkin, current PRODUCT QUESTION when
                        present, Plane state, current acceptance lease, child terminal dispositions, completion evidence when
                        acceptance is requested, and one exact unmet outcome when acceptance fails.
                        """))
                .to("tack:thought-enter?name=po-acceptance&maxTokens=250")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Curate or evaluate exactly one compact canonical product page for the selected product parent.

                        For an unresolved PRODUCT QUESTION, answer exactly the returned product question using the mission
                        and current canonical product intent. Repair an unreadable or malformed acceptance page when needed,
                        or clarify ambiguous scope/Gherkin when product judgment is genuinely required. Preserve established
                        product intent and do not invent technical decomposition. The answer must leave a usable canonical
                        acceptance reference for delivery.

                        For Backlog product work, define one coherent user-facing outcome with value, scope boundaries,
                        and compact observable Given/When/Then acceptance. Keep technical decomposition out of this page.

                        For an In Progress product parent awaiting acceptance, proceed only when the current PM
                        PRODUCT ACCEPTANCE READY is newer than every PRODUCT ACCEPTANCE FEEDBACK, PRODUCT QUESTION, and
                        PRODUCT HANDOFF on the parent. Verify every implementation child is Done or explicitly Cancelled as
                        superseded/non-required, no active TOPOLOGY INVESTIGATION affects this product, and every current
                        TECHNICAL FINDING has a later PM TOPOLOGY_APPLIED disposition. Preserve the current scope and Gherkin.
                        Evaluate only durable completion evidence supplied by delivery. Record which existing scenarios are
                        satisfied or one exact unmet scenario. Never rewrite acceptance criteria to fit implementation.
                        If required evidence is missing, record the gap and stop rather than searching for substitute evidence.
                        """))
                .to("tack:act?id=po-canonical-acceptance-update&toolProfile=" + SourceControlWriteToolProfile.ID)
                .to("tack:thought-exit?name=po-acceptance");

        from("direct:po-plane-state-handoff")
                .routeId("po-plane-state-handoff")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Update only the selected PRODUCT PARENT using Plane's real states:
                        Backlog, Todo, In Progress, Done, Cancelled.

                        For an answered PRODUCT QUESTION on a Todo or In Progress product parent, keep the parent's
                        current delivery state unchanged and add one newer PRODUCT HANDOFF comment that explicitly
                        references/resolves the latest PRODUCT QUESTION, links the usable canonical acceptance page,
                        and states the clarified product outcome. That newer handoff invalidates every older PRODUCT
                        ACCEPTANCE READY and is the authoritative Product Owner -> Project Management return edge.
                        Do not create implementation work yourself.

                        For a Backlog product slice whose user scope and Gherkin acceptance are usable, move it
                        Backlog -> Todo. This state transition is the authoritative initial handoff to Project Management.
                        Add one concise PRODUCT HANDOFF comment with the canonical acceptance page, priority, open
                        product questions if any, and requested delivery outcome. PM must not need the comment to
                        discover initial Todo eligibility; Todo is authoritative.

                        For an In Progress product parent awaiting acceptance, move it In Progress -> Done only when the
                        current PM PRODUCT ACCEPTANCE READY is newer than every PRODUCT ACCEPTANCE FEEDBACK, PRODUCT QUESTION,
                        and PRODUCT HANDOFF, every required Gherkin scenario has durable Done coverage, every child is Done
                        or explicitly Cancelled as superseded/non-required, no active topology investigation affects the
                        product, and every current TECHNICAL FINDING has a later PM TOPOLOGY_APPLIED disposition. If acceptance
                        fails, keep it In Progress and add or refresh one PRODUCT ACCEPTANCE FEEDBACK naming the exact unmet
                        existing scenario or lease/disposition gap. That feedback immediately invalidates the READY that
                        triggered this acceptance pass. Do not move rejected work back to Backlog or Todo.

                        Cancelled implementation children never count as delivered acceptance coverage. Accept a parent
                        with historical Cancelled children only when every required behavior has superseding durable Done
                        coverage or the corresponding product scope itself was explicitly cancelled.

                        Do not change implementation-child states, assign developers, or create delivery tasks.
                        """))
                .to("tack:act?id=po-plane-handoff-update&toolProfile=" + ProjectManagementToolProfile.ID);

        from("direct:po-cycle-closeout")
                .routeId("po-cycle-closeout")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Continue a useful product conversation only when a teammate needs product judgment.
                        Otherwise stay quiet. Link the product parent or canonical acceptance page when useful.
                        """))
                .to("tack:act?id=po-team-chat-closeout&toolProfile=" + TeamChatToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Make one small product-documentation curation improvement only when obvious from current
                        evidence. Preserve navigation and durable decisions; leave work tracking in Plane.
                        """))
                .to("tack:act?id=po-follow-up-note&toolProfile=" + SourceControlWriteToolProfile.ID);
    }
}
