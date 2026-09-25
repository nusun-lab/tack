package us.nusun.llmshare.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
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
    public List<RoutineDefinition> routines() { return List.of(routine()); }

    public RoutineDefinition routine() {
        return RoutineDefinition.builder(ID)
                .name("Product Owner")
                .main("Define, prioritize, and accept product outcomes.", "direct:example-software-team-product-owner")
                .subroutine("acceptance-refinement", "Clarify one product outcome and its acceptance.", "direct:example-software-team-product-owner-acceptance-refinement")
                .subroutine("priority-handoff", "Advance one product outcome to its next lifecycle step.", "direct:example-software-team-product-owner-priority-handoff")
                .defaultLane("high-pass")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-software-team-product-owner")
                .routeId("example-software-team-product-owner")
                .to("direct:po-product-owner-root-enter")
                .setProperty("decision.product-owner-mission-complete.result", constant("no"))
                .loopDoWhile(exchangeProperty("decision.product-owner-mission-complete.result").isNotEqualTo("yes"))
                    .to("direct:po-orientation")
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Choose exactly one highest-priority Product Owner focus for the next focused pass. Operating
                            model: PO should normally stay ahead of PM with several ready PRODUCT slices, typically about
                            3-5 when remaining mission scope safely supports that depth. Count only Plane items classified
                            work:product. PM work:implementation children never satisfy Product Owner backlog depth. An In
                            Progress product slice does NOT prevent defining independent future slices. Allowed focus
                            values, in priority order when genuinely actionable:
                            - missing-basics: the shared Plane/docs/chat foundation is not usable for product work. An empty
                              source repository is not a product-work blocker when the
                              repository itself exists and is accessible.
                            - product-work: there is an unresolved PRODUCT QUESTION, a completed product acceptance
                              candidate, a work:product Backlog slice needing definition/handoff, an unrepresented required
                              user-facing outcome, or the delivery-ready Todo work:product backlog is below the useful
                              buffer. When the product buffer is shallow and distinct mission outcomes remain, prefer
                              creating/defining additional product outcomes over revisiting already-ready
                              work that needs no Product Owner judgment.
                            - coordination: a fresh inbound team conversation, question, or clarification needs Product
                              Owner judgment. Outbound awareness after product work is part of that productive flow and
                              does not compete for focus.
                            - closeout: a concrete product documentation/chat curation
                              follow-up is useful after durable product work.
                            - wait: none of the above is useful now. A shallow product backlog while safely definable
                              mission scope remains is actionable product work, not a reason to wait. Plane real states are
                              authoritative lifecycle state. work:product/work:implementation labels are classification
                              only. Comments provide evidence/context only and must not become named workflow states or
                              approval leases. Fresh orientation evidence outranks older HOLD/wait
                              conclusions. Choose exactly one allowed focus value.
                            """))
                    .to("tack:decide?id=po-focus&choices=missing-basics,product-work,coordination,closeout,wait")
                    .toD("direct:po-focus-${exchangeProperty.decision.po-focus.result}")
                .end()
                .end()
                .to("direct:po-product-owner-root-exit");

        from("direct:po-focus-missing-basics")
                .routeId("po-focus-missing-basics")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Focused missing-foundation response: communicate only when useful and do not begin product work.
                        """))
                .to("tack:thought-enter?name=po-missing-basics-focus&maxTokens=250")
                .to("direct:po-missing-basics-closeout")
                .to("tack:thought-exit?name=po-missing-basics-focus");

        from("direct:po-focus-product-work")
                .routeId("po-focus-product-work")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Focused product work: maintain a useful collection of work:product slices ahead of PM. Establish
                        distinct foreseeable outcomes in Backlog together when the mission supports them, then refine
                        the nearest slices to Todo as acceptance becomes usable. Plane lifecycle state is only Backlog,
                        Todo, In Progress, Done, or Cancelled; do not invent readiness states.
                        """))
                .to("tack:thought-enter?name=po-product-work-focus&maxTokens=400")
                .to("direct:po-product-work-focus")
                .to("tack:thought-exit?name=po-product-work-focus");

        from("direct:po-focus-coordination")
                .routeId("po-focus-coordination")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Focused Product Owner coordination for one fresh inbound scope, acceptance, or product-judgment
                        exchange. Product-work routes handle outbound awareness caused by their own changes.
                        """))
                .to("tack:thought-enter?name=po-coordination-focus&maxTokens=250")
                .to("direct:po-team-chat-sync")
                .to("tack:thought-exit?name=po-coordination-focus");

        from("direct:po-focus-closeout")
                .routeId("po-focus-closeout")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Focused product closeout curation when a durable conversation or
                        documentation improvement is concretely useful.
                        """))
                .to("tack:thought-enter?name=po-closeout-focus&maxTokens=250")
                .to("direct:po-cycle-closeout")
                .to("tack:thought-exit?name=po-closeout-focus");

        from("direct:po-focus-wait")
                .routeId("po-focus-wait")
                .to("direct:po-completion-focus")
                .choice()
                    .when(exchangeProperty("decision.product-owner-mission-complete.result").isNotEqualTo("yes"))
                        .delay(120000)
                .end();

        from("direct:po-orientation")
                .routeId("po-orientation")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read the smallest fresh current-mission slice needed to rank Product Owner focus. Determine
                        only: whether shared basics are usable; whether there is an unresolved PRODUCT QUESTION; whether
                        a work:product In Progress parent now has all required implementation children terminal with
                        durable completion evidence; whether work:product Backlog slices need definition; whether
                        genuinely unrepresented user-facing outcomes remain; Todo work:product depth; one fresh inbound
                        team exchange needing product judgment; one concrete
                        closeout need. Count only explicitly work:product-classified items
                        as product backlog. A work:implementation item is never Product Owner backlog. If labels were
                        not returned, classification is unknown rather than inferred from wording. Prefer verified
                        project/docs ids already in thought rather than rediscovering workspaces. Report observations
                        only. Do not recommend a next focus, preserve an older HOLD conclusion against newer evidence,
                        mutate, or perform selected product work here.
                        """))
                .to("tack:act?id=po-orientation&toolProfile=" + WorkspaceReadonlyToolProfile.ID);

        from("direct:po-product-work-focus")
                .routeId("po-product-work-focus")
                .setProperty("poFocusedProductIterations", constant(0))
                .setProperty("poFocusedProductContinue", constant(true))
                .setProperty("poFocusedProductMadeProgress", constant(false))
                .setHeader(TackHeaders.PROMPT, constant("""
                        Inspect the mission and current work:product coverage once. When distinct required user-facing
                        outcomes are still unrepresented, create a coherent COLLECTION of product parents in Backlog now
                        rather than discovering one per pass. Keep each slice independently valuable and bounded. Create
                        only outcomes supported by mission intent; refinement can happen later. Do not create
                        work:implementation children. Use only Plane states Backlog, Todo, In Progress, Done, Cancelled;
                        do not encode readiness in comments or invented states. Existing slices must not be duplicated.
                        """))
                .to("tack:act?id=po-product-backlog-seed&toolProfile=" + ProjectManagementToolProfile.ID)
                .choice()
                    .when(exchangeProperty("act.po-product-backlog-seed.durableProgress").isEqualTo(true))
                        .setProperty("poFocusedProductMadeProgress", constant(true))
                .end()
                .setProperty("decision.po-product-backlog-deep-enough.result", constant("no"))
                .loopDoWhile(simple("${exchangeProperty.poFocusedProductContinue} == true && ${exchangeProperty.poFocusedProductIterations} < 6"))
                    .setProperty("poFocusedProductIterations", simple("${exchangeProperty.poFocusedProductIterations++}"))
                    .setProperty("poFocusedProductContinue", constant(false))
                    .to("direct:po-select-product-work")
                    .choice()
                        .when(exchangeProperty("decision.product-owner-has-work.result").isEqualTo("yes"))
                            .to("direct:po-acceptance-work")
                            .to("direct:po-plane-state-handoff")
                            .choice()
                                .when(exchangeProperty("act.po-canonical-acceptance-update.durableProgress").isEqualTo(true))
                                    .setProperty("poFocusedProductContinue", constant(true))
                                    .setProperty("poFocusedProductMadeProgress", constant(true))
                            .end()
                            .choice()
                                .when(exchangeProperty("act.po-plane-handoff-update.durableProgress").isEqualTo(true))
                                    .setProperty("poFocusedProductContinue", constant(true))
                                    .setProperty("poFocusedProductMadeProgress", constant(true))
                            .end()
                    .end()
                    .to("direct:po-product-backlog-depth-check")
                    .choice()
                        .when(exchangeProperty("decision.po-product-backlog-deep-enough.result").isEqualTo("yes"))
                            .setProperty("poFocusedProductContinue", constant(false))
                        .otherwise()
                            .setProperty("poFocusedProductContinue", constant(true))
                    .end()
                .end()
                .choice()
                    .when(exchangeProperty("poFocusedProductMadeProgress").isEqualTo(true))
                        .to("direct:po-team-chat-sync")
                .end();

        from("direct:po-product-backlog-depth-check")
                .routeId("po-product-backlog-depth-check")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read only current work:product queue depth in Plane. Count delivery-ready Todo product slices
                        carrying the work:product label with usable canonical acceptance. Never count
                        work:implementation children. Note unresolved PRODUCT QUESTION or completed In Progress product
                        acceptance work, and whether additional distinct required user-facing mission outcomes can still
                        be safely represented. Existing In Progress product work does not reduce desired ready-buffer
                        depth. Do not mutate or inspect implementation details. If labels are absent from the current
                        projection, read labels rather than guessing classification.
                        """))
                .to("tack:act?id=po-product-backlog-depth-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Is PO sufficiently ahead of Project Management? Answer no whenever a current product question or
                        completed acceptance candidate requires PO action. Otherwise normally require about 3-5 usable
                        Todo work:product slices when enough safely definable mission scope remains. Answer no when the
                        queue is shallower and another distinct required user-facing outcome can be defined without
                        speculation, even when existing product parents are already In Progress. Never count
                        work:implementation items toward this depth. Answer yes with a shallower queue only when
                        remaining mission size or missing product evidence makes further safe definition inappropriate.
                        """))
                .to("tack:decide?id=po-product-backlog-deep-enough");

        from("direct:po-completion-focus")
                .routeId("po-completion-focus")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Review mission completion once using the smallest authoritative read and known ids. One verified
                        unfinished work:product user-facing outcome or unresolved product question is enough to conclude
                        not complete. Plane real state is authoritative;
                        comments are evidence/context only. Do not mutate.
                        """))
                .to("tack:act?id=po-mission-status-review&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Is the entire mission complete from product ownership? Answer yes only when every required
                        user-facing outcome is represented by work:product items and Done against its current intended
                        acceptance criteria with no unresolved product question/decision or acceptance follow-up. Todo
                        or In Progress product work means not complete.
                        """))
                .to("tack:decide?id=product-owner-mission-complete");

        from("direct:example-software-team-product-owner-acceptance-refinement")
                .routeId("example-software-team-product-owner-acceptance-refinement")
                .to("direct:po-product-owner-root-enter")
                .to("direct:po-project-basics-check")
                .choice()
                    .when(exchangeProperty("decision.product-owner-has-project-basics.result").isEqualTo("yes"))
                        .to("direct:po-select-product-work")
                        .choice()
                            .when(exchangeProperty("decision.product-owner-has-work.result").isEqualTo("yes"))
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
                    .when(exchangeProperty("decision.product-owner-has-project-basics.result").isEqualTo("yes"))
                        .to("direct:po-select-product-work")
                        .choice()
                            .when(exchangeProperty("decision.product-owner-has-work.result").isEqualTo("yes"))
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
                        Durable product anchors: verified project/docs/chat ids, work:product queue depth, selected
                        product parent/Plane state, returned product questions, canonical acceptance, child completion
                        evidence for acceptance, and source repository.
                        """))
                .to("tack:thought-enter?name=product-owner-routine&maxTokens=500");

        from("direct:po-product-owner-root-exit")
                .routeId("po-product-owner-root-exit")
                .to("tack:thought-exit?name=product-owner-routine");

        from("direct:po-project-basics-check")
                .routeId("po-project-basics-check")
                .setProperty("decision.product-owner-has-project-basics.result", constant("no"))
                .setHeader(TackHeaders.PROMPT, constant("""
                        Verify product project basics once without mutation. Product work requires usable Plane,
                        docs/navigation, team chat, and an accessible source repository anchor. The source repository
                        may be empty; lack of main/commits is not a Product Owner blocker. Prefer known verified ids
                        from thought. One confirmed missing required basic is enough to stop.
                        Do not repeatedly rediscover the workspace.
                        """))
                .to("tack:act?id=po-workspace-basics-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Are project basics present and linked enough to do product work? Treat an existing
                        accessible empty source repository as sufficient.
                        """))
                .to("tack:decide?id=product-owner-has-project-basics");

        from("direct:po-missing-basics-closeout")
                .routeId("po-missing-basics-closeout")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Project basics are not usable for product work. Initiate or reply to one concise setup question
                        only when it would help PM repair a concrete missing basic sooner; otherwise stay quiet. An
                        empty-but-accessible source repo is not a missing basic. Do not create product work
                        until the actual shared workspace is usable.
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
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                            Current product work unit: one work:product parent, real Plane state, user scope/Gherkin,
                            returned product question or completion evidence, and next distinct
                            user outcome when queue depth remains shallow.
                            """))
                    .to("tack:thought-enter?name=po-cycle&maxTokens=300")
                    .to("direct:po-select-product-work")
                    .choice()
                        .when(exchangeProperty("decision.product-owner-has-work.result").isEqualTo("yes"))
                            .to("direct:po-acceptance-work")
                            .choice().when(exchangeProperty("act.po-canonical-acceptance-update.durableProgress").isEqualTo(true))
                                .setProperty("poMadeProgress", constant(true)).endChoice()
                            .to("direct:po-plane-state-handoff")
                            .choice().when(exchangeProperty("act.po-plane-handoff-update.durableProgress").isEqualTo(true))
                                .setProperty("poMadeProgress", constant(true)).endChoice()
                    .end()
                    .to("tack:thought-exit?name=po-cycle")
                .end()
                .to("direct:po-cycle-closeout");

        from("direct:po-team-chat-sync")
                .routeId("po-team-chat-sync")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Communicate one concise product-awareness update when the current product flow materially created
                        or refined the product backlog, moved a slice to Todo, clarified product intent, or completed
                        acceptance. Use current work context directly; read chat only as narrowly as needed to avoid
                        duplication or answer a related fresh question. Plane state is authoritative and chat is never a
                        lifecycle flag or acknowledgment gate. Link authoritative work:product/canonical acceptance when
                        useful. Avoid generic status.
                        """))
                .to("tack:act?id=po-team-chat-sync&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:po-select-product-work")
                .routeId("po-select-product-work")
                .setProperty("decision.product-owner-has-work.result", constant("no"))
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Product selection: one work:product parent only, never a work:implementation child. Prefer
                        unresolved product judgment, then completed acceptance candidate, then Backlog definition, then
                        create one missing distinct required outcome when ready-buffer depth is shallow.
                        """))
                .to("tack:thought-enter?name=po-selection&maxTokens=250")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Using fresh Plane evidence, select exactly one PRODUCT PARENT carrying work:product, never a
                        work:implementation child. Classification labels are
                        authoritative for role ownership; item wording is not.
                        
                        First prefer a Todo/In Progress work:product parent with an unresolved product-owned question
                        from delivery. Treat question content and later clarifying evidence as
                        context; comments are not hidden workflow state.
                        
                        Otherwise prefer an In Progress work:product parent for acceptance when all required
                        implementation children are Done (or explicitly Cancelled as superseded/non-required with
                        replacement coverage), no active topology investigation affects it, and durable current
                        completion/validation evidence exists for the acceptance criteria. Do not require
                        a magic PM PRODUCT ACCEPTANCE READY phrase.
                        
                        Otherwise select one existing Backlog work:product slice needing definition. Collection creation
                        belongs to the backlog-seeding step, not this selector. Existing In Progress product work does
                        not block defining independent future product slices.
                        
                        Never select, refine, promote, or accept an item carrying work:implementation as Product Owner
                        work. If classification labels are missing from the read, retrieve labels instead of guessing
                        from name/description. Product parents describe observable user value/acceptance, never code
                        patches. Do not create implementation children or assign developers.
                        """))
                .to("tack:act?id=po-backlog-review&toolProfile=" + ProjectManagementToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Is exactly one work:product parent selected for useful PO work now: unresolved product judgment,
                        Backlog definition/handoff, completed In Progress acceptance candidate based on real child
                        states/evidence, or a newly created missing product outcome?
                        Answer no for any work:implementation item.
                        """))
                .to("tack:decide?id=product-owner-has-work")
                .choice()
                    .when(exchangeProperty("decision.product-owner-has-work.result").isEqualTo("yes"))
                        .setHeader(TackHeaders.PROMPT, constant("""
                                Read only the selected work:product canonical product page, direct Plane parent/child states,
                                current product-question context when relevant, and direct durable completion evidence needed
                                for acceptance. Prefer known ids. Do not scan the whole documentation
                                repository or depend on named workflow comments.
                                """))
                        .to("tack:act?id=po-product-context-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .end()
                .to("tack:thought-exit?name=po-selection");

        from("direct:po-acceptance-work")
                .routeId("po-acceptance-work")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Selected work:product parent: user value, scope/Gherkin, current product question when present,
                        Plane state, child terminal dispositions, direct completion evidence when acceptance is
                        requested, and one exact unmet outcome when acceptance fails.
                        """))
                .to("tack:thought-enter?name=po-acceptance&maxTokens=250")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Curate or evaluate exactly one compact canonical product page for the selected work:product
                        parent. For unresolved product judgment, answer exactly the returned question using mission and
                        current product intent, leaving usable canonical acceptance. For Backlog work, define one
                        coherent user-facing outcome with scope boundaries and compact observable Given/When/Then
                        acceptance; keep technical decomposition out. For an In Progress acceptance candidate, require
                        real child states and durable completion/validation evidence, not a named PM approval comment.
                        Preserve current scope/Gherkin, evaluate only existing acceptance, and never rewrite criteria to
                        fit implementation. If evidence is missing, record the exact gap and stop.
                        """))
                .to("tack:act?id=po-canonical-acceptance-update&toolProfile=" + SourceControlWriteToolProfile.ID)
                .to("tack:thought-exit?name=po-acceptance");

        from("direct:po-plane-state-handoff")
                .routeId("po-plane-state-handoff")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Update only the selected work:product PRODUCT PARENT using Plane real states: Backlog, Todo, In
                        Progress, Done, Cancelled. Comments are concise evidence/context, never named workflow states.
                        Never mutate a work:implementation item in this Product Owner handoff.
                        
                        For answered product questions on Todo/In Progress parents, keep current lifecycle state
                        unchanged and add concise context linking the clarified canonical acceptance when useful. Do not
                        require PM to discover readiness from a magic comment.
                        
                        For a Backlog product slice whose user scope/Gherkin are usable, move Backlog -> Todo. That
                        state transition plus work:product classification is the authoritative initial handoff to PM.
                        Add only concise context/links when useful.
                        
                        For an In Progress product parent, move In Progress -> Done only when every required acceptance
                        scenario has durable Done implementation coverage/evidence, every required child is Done or
                        explicitly Cancelled as superseded/non-required with replacement coverage, and no unresolved
                        topology/product question remains. If acceptance fails, keep In Progress and leave concise
                        evidence naming the exact unmet existing scenario/gap. Do not manufacture PRODUCT ACCEPTANCE
                        READY/FEEDBACK/HANDOFF state machines in comments.
                        
                        Do not change implementation-child states, assign developers, or create delivery tasks.
                        """))
                .to("tack:act?id=po-plane-handoff-update&toolProfile=" + ProjectManagementToolProfile.ID);

        from("direct:po-cycle-closeout")
                .routeId("po-cycle-closeout")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Initiate or continue one useful product conversation when a newly ready slice, product
                        clarification, acceptance result, or teammate question would help someone act sooner. Do not
                        require an existing thread. Otherwise stay quiet. Link
                        authoritative product evidence when useful.
                        """))
                .to("tack:act?id=po-team-chat-closeout&toolProfile=" + TeamChatToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Make one small product-documentation curation improvement only when obvious from current
                        evidence. Preserve navigation/durable decisions; leave work tracking in Plane.
                        """))
                .to("tack:act?id=po-follow-up-note&toolProfile=" + SourceControlWriteToolProfile.ID);
    }
}
