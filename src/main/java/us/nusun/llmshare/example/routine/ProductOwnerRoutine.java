package us.nusun.llmshare.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.example.tool.ProductOwnershipToolProfile;
import us.nusun.llmshare.example.tool.TeamChatToolProfile;
import us.nusun.llmshare.example.tool.WorkspaceReadonlyToolProfile;
import us.nusun.llmshare.framework.inventory.RoutineProvider;
import us.nusun.llmshare.framework.model.LlmPassType;
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
                .main("Own epic product outcomes from definition through evidence-based acceptance.",
                        "direct:example-software-team-product-owner")
                .subroutine("epic-definition", "Define and prioritize product Epics.", "direct:po-epic-definition")
                .subroutine("epic-acceptance", "Accept an Epic using current completion evidence.",
                        "direct:po-epic-acceptance")
                .subroutine("defect-triage", "Validate QA defects and decide product priority.",
                        "direct:po-defect-triage")
                .defaultLane("high-pass")
                .withLaneBinding(LlmPassType.SYNTHESIS, "low-pass")
                .withLaneBinding(LlmPassType.CONSOLIDATION, "low-pass")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-software-team-product-owner")
                .routeId("example-software-team-product-owner")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Durable product anchors: mission value, epic label, prioritized active/ready Epic sets,
                        acceptance intent, QA evidence, open defects, product questions, and viability findings.
                        """))
                .to("tack:thought-enter?name=po-root&maxTokens=450")
                .setProperty("decision.product-owner-mission-complete.result", constant("no"))
                .loopDoWhile(exchangeProperty("decision.product-owner-mission-complete.result").isNotEqualTo("yes"))
                    .to("direct:po-orientation")
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Choose exactly one highest-value Product Owner focus.
                            - defect-triage: a QA-created Backlog defect needs product validation, Epic association,
                              priority, or rejection with clarified test intent.
                            - acceptance: one In Progress Epic has implementation and QA evidence sufficient for a product
                              acceptance decision.
                            - epic-work: a Todo/In Progress/Done/Cancelled Epic lacks PO ownership; required outcomes are
                              missing/weak; a ready Epic lacks usable Given/When/Then acceptance; the ready Epic backlog is
                              too shallow; or fewer than three Epics are In Progress and a Todo Epic has PM-prepared Story
                              coverage plus current downstream capacity to begin delivery.
                            - qa-feedback: QA raised an acceptance ambiguity or evidence question requiring product judgment.
                            - viability: evidence suggests product scope/value or mission interpretation needs Director input.
                            - coordination: another fresh product question needs a concise response.
                            - complete: all required mission outcomes are represented by epic work items, every
                              required Epic is accepted Done, and no unresolved product judgment, acceptance-blocking
                              defect, or viability question remains.
                            - wait: the mission is not complete and none of the other focuses is useful now.
                            Keep the In Progress Epic set shallow, normally 2-3, while maintaining a deeper Todo runway of
                            fully defined product-ready Epics. Choose exactly one value.
                            """))
                    .to("tack:decide?id=po-focus&choices=defect-triage,acceptance,epic-work,qa-feedback,viability,coordination,complete,wait")
                    .toD("direct:po-focus-${exchangeProperty.decision.po-focus.result}")
                .end()
                .end()
                .to("tack:thought-exit?name=po-root");

        from("direct:po-orientation")
                .routeId("po-orientation")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read the smallest current-mission slice needed to rank Product Owner focus. Identify product
                        outcomes only from the attached epic label; Plane type_id is unrelated to Tack classification.
                        Never infer an Epic from title, hierarchy, state, or ownership. Capture: active In Progress Epic count; ready Todo Epic depth; missing mission
                        outcomes; Given/When/Then acceptance for selected active/ready Epics; ownership of every
                        non-Backlog Epic; whether each Todo Epic has usable Gherkin and implementation Story coverage;
                        implementation/test child progress when relevant; open defects plus any PO-recorded
                        acceptance-impact disposition; QA evidence/ambiguity; one fresh product exchange; one viability concern.
                        A sparse work-item list may omit parent; retrieve only a selected child when its Epic parent is
                        needed. Report observations only. Do not mutate.
                        """))
                .to("tack:act?id=po-orientation&toolProfile=" + WorkspaceReadonlyToolProfile.ID);

        from("direct:po-focus-epic-work")
                .routeId("po-focus-epic-work")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Focused product work: maintain complete Epic coverage, usable Given/When/Then acceptance, a shallow
                        2-3 Epic active set, and a deeper Todo runway that PM can pre-decompose without activating.
                        """))
                .to("tack:thought-enter?name=po-epic-work-focus&maxTokens=500")
                .to("direct:po-epic-work-loop")
                .to("tack:thought-exit?name=po-epic-work-focus")
                .to("direct:po-product-awareness");

        from("direct:po-epic-work-loop")
                .routeId("po-epic-work-loop")
                .setProperty("poEpicWorkIterations", constant(0))
                .setProperty("poEpicWorkContinue", constant(true))
                .loopDoWhile(simple("${exchangeProperty.poEpicWorkContinue} == true && ${exchangeProperty.poEpicWorkIterations} < 6"))
                    .setProperty("poEpicWorkIterations", simple("${exchangeProperty.poEpicWorkIterations++}"))
                    .setProperty("poEpicWorkContinue", constant(false))
                    .to("direct:po-epic-definition")
                    .choice()
                        .when(exchangeProperty("act.po-epic-work.durableProgress").isEqualTo(true))
                            .setProperty("poEpicWorkContinue", constant(true))
                    .end()
                .end();

        from("direct:po-epic-definition")
                .routeId("po-epic-definition")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Make one coherent Epic lifecycle advance. Ensure the attached epic classification label exists.
                        Product outcomes are
                        top-level Plane work items carrying that label. Create/refine only independently valuable outcomes
                        supported by the mission, with useful product priority.

                        Epic lifecycle semantics are strict:
                        - Backlog: product outcome exists but is not yet sufficiently defined.
                        - Todo: product-ready and delivery-ready for planning, with usable Given/When/Then acceptance.
                        - In Progress: downstream delivery is actively consuming the Epic.
                        - Done: Product Owner accepted the promised behavior.

                        Every Epic must contain concrete externally observable Given/When/Then acceptance before Todo or
                        In Progress. Prose may add context but never replaces Gherkin. Any Epic outside Backlog must be
                        assigned to the active Product Owner. Backlog Epics may remain unassigned.

                        Maintain a deeper Todo runway, but normally no more than three Epics In Progress. Promotion from
                        Todo to In Progress requires fewer than three active Epics, reasonable implementation Story
                        coverage already prepared by PM, and current downstream capacity that can consume the Epic.
                        Product readiness alone never activates an Epic. If three Epics are already In Progress, continue
                        refining/preparing Todo Epics without activating another. A defect-driven reopen of a previously
                        Done Epic is an explicit exception and may temporarily exceed the normal active limit.

                        Attach epic explicitly to every product outcome you create or classify.
                        Define product behavior only; do not prescribe implementation mechanics in Gherkin. Do not create
                        or sequence story implementation work, assign engineers, create test work, or own
                        repair routing. Delivery gaps may be surfaced to PM, but PM owns implementation topology.
                        """))
                .to("tack:act?id=po-epic-work&toolProfile=" + ProductOwnershipToolProfile.ID);

        from("direct:po-focus-defect-triage")
                .routeId("po-focus-defect-triage")
                .to("direct:po-defect-triage");

        from("direct:po-defect-triage")
                .routeId("po-defect-triage")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Validate one QA-created Backlog defect. Read its reproducible evidence, originating test,
                        candidate Epic context, and expected versus actual behavior.

                        If it is a real product defect, set its parent to the affected Epic, assign product priority, and
                        explicitly record whether it invalidates the Epic's current acceptance as
                        acceptance-blocking=yes or acceptance-blocking=no. An acceptance-blocking Defect must move to Todo
                        for repair; if the affected Epic was Done, reopen that Epic to In Progress first. A non-blocking
                        Defect may remain Backlog when accepted but deferred, or move to Todo when product priority warrants
                        engineering action now. Leave accepted Defects unassigned so PM can route Todo repair work.

                        If it is not a product defect because acceptance/test intent was wrong or ambiguous, Cancel the
                        Defect with the product disposition and clarify the affected Epic acceptance when needed. Record
                        the clarified expected behavior on the originating test and explicitly request QE retest.
                        Do not change the Test lifecycle; QE owns Test state and will return it to Todo. If it is a
                        duplicate, Cancel it and reference the canonical finding. Do not create repair work or assign
                        engineers here.
                        """))
                .to("tack:act?id=po-defect-triage&toolProfile=" + ProductOwnershipToolProfile.ID)
                .to("direct:po-product-awareness");

        from("direct:po-focus-acceptance")
                .routeId("po-focus-acceptance")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Focused acceptance for one active Epic: exact Gherkin, terminal delivery/test evidence, open
                        defects, QA behavioral proof, product gaps, and the final Epic lifecycle decision.
                        """))
                .to("tack:thought-enter?name=po-acceptance-focus&maxTokens=500")
                .to("direct:po-epic-acceptance")
                .to("tack:thought-exit?name=po-acceptance-focus")
                .to("direct:po-product-awareness");

        from("direct:po-epic-acceptance")
                .routeId("po-epic-acceptance")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Decide acceptance for one selected active Epic against its current PO-authored Given/When/Then
                        scenarios. Read the Epic plus only its child work/evidence needed for the decision. Move the Epic
                        to Done only when its promised behavior is demonstrated by current
                        QA evidence, required Story/Test children are terminal, and no relevant accepted defect whose
                        PO disposition says acceptance-blocking=yes remains nonterminal. Accepted non-blocking Defects may
                        remain Backlog without preventing Epic acceptance. A merged PR alone is not acceptance. If evidence
                        is insufficient, keep the Epic In Progress and record the exact missing product/evidence condition
                        without creating implementation work.
                        """))
                .to("tack:act?id=po-epic-acceptance&toolProfile=" + ProductOwnershipToolProfile.ID);

        from("direct:po-focus-qa-feedback")
                .routeId("po-focus-qa-feedback")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Resolve one QA-raised acceptance ambiguity or evidence question. Clarify the affected Epic's
                        acceptance intent and update that Epic when necessary. Preserve QA ownership of test engineering.
                        Do not convert uncertainty into a Developer defect merely to keep flow moving.
                        """))
                .to("tack:act?id=po-qa-feedback&toolProfile=" + ProductOwnershipToolProfile.ID)
                .to("direct:po-product-awareness");

        from("direct:po-focus-viability")
                .routeId("po-focus-viability")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Discuss one evidence-backed product viability concern/opportunity with Director: changed mission
                        assumption, disproportionate implementation cost, weak user value, emerging stronger value, or
                        mission/product mismatch. PO retains Epic/backlog authority; Director supplies mission-level
                        judgment. Ask only when that judgment can change product direction.
                        """))
                .to("tack:act?id=po-director-viability&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:po-focus-coordination")
                .routeId("po-focus-coordination")
                .to("direct:po-product-awareness");

        from("direct:po-product-awareness")
                .routeId("po-product-awareness")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Use the current-mission shared channel. Send/reply once only when an Epic lifecycle change,
                        defect triage, acceptance decision, product clarification, or viability question will help PM, QA,
                        Architect, Developer, or Director act sooner. Missing channel access is onboarding work, not a
                        reason to use a DM. Avoid generic status.
                        """))
                .to("tack:act?id=po-product-awareness&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:po-focus-complete")
                .routeId("po-focus-complete")
                .setProperty("decision.product-owner-mission-complete.result", constant("yes"));

        from("direct:po-focus-wait")
                .routeId("po-focus-wait")
                .delay(120000);
    }
}
