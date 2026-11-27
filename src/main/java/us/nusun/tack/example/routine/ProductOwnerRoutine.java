package us.nusun.tack.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.tack.example.tool.ProductOwnershipToolProfile;
import us.nusun.tack.example.tool.TeamChatToolProfile;
import us.nusun.tack.example.tool.WorkspaceReadonlyToolProfile;
import us.nusun.tack.framework.routine.RoutineProvider;
import us.nusun.tack.framework.inference.LlmPassType;
import us.nusun.tack.framework.routine.RoutineDefinition;
import us.nusun.tack.framework.routine.TackHeaders;

import java.util.List;

import static org.apache.camel.builder.PredicateBuilder.and;
import static org.apache.camel.builder.PredicateBuilder.or;

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
                .subroutine("epic-definition", "Define and prioritize product Epics.", "direct:po-focus-epic-work")
                .subroutine("epic-acceptance", "Accept an Epic using current completion evidence.",
                        "direct:po-focus-acceptance")
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
                        Mission value, exact Kaneo workspace/project IDs and slug, authenticated PO ID, prioritized Epic
                        locators, acceptance intent, QA evidence, open Defects, product questions and viability findings.
                        """))
                .to("tack:thought-enter?name=po-root&maxTokens=450")
                .setProperty("decision.product-owner-mission-complete.result", constant("no"))
                .loopDoWhile(exchangeProperty("decision.product-owner-mission-complete.result").isNotEqualTo("yes"))
                    .to("direct:po-orientation")
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Choose exactly one highest-value Product Owner focus.
                            - defect-triage: a QA-created planned defect needs product validation, Epic association,
                              priority, or rejection with clarified test intent.
                            - acceptance: one in-progress Epic has implementation and QA evidence sufficient for a product
                              acceptance decision.
                            - epic-work: a to-do or in-progress Epic lacks PO ownership; required outcomes are
                              missing/weak; a ready Epic lacks usable Given/When/Then acceptance; the ready Epic backlog is
                              too shallow; or active capacity allows a to-do Epic with its first executable Story ready,
                              underway or already delivered to become in-progress.
                            - qa-feedback: QA raised an acceptance ambiguity or evidence question requiring product judgment.
                            - viability: evidence suggests product scope/value or mission interpretation needs Director input.
                            - coordination: another fresh product question needs a concise response.
                            - complete: all required mission outcomes are represented by epic tasks, every
                              required Epic is accepted and done, and no unresolved product judgment, acceptance-blocking
                              defect, or viability question remains.
                            - wait: the mission is not complete and none of the other focuses is useful now.
                            Keep the in-progress Epic set shallow, normally 2-3, while maintaining a deeper to-do runway of
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
                        Use project task lists to assess the in-progress Epic set, to-do runway and missing mission outcomes.
                        Check selected Epics for PO ownership, Gherkin, PM-prepared Story coverage, QA evidence and blocking
                        Defects. Find untriaged planned Defects and relevant product questions. Read only the related evidence
                        needed to choose a focus.
                        """))
                .to("tack:act?id=po-orientation&toolProfile=" + WorkspaceReadonlyToolProfile.ID);

        from("direct:po-focus-epic-work")
                .routeId("po-focus-epic-work")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Focused product work: maintain complete Epic coverage, usable Given/When/Then acceptance, a shallow
                        2-3 Epic active set, and a deeper to-do runway that PM can pre-decompose without activating.
                        """))
                .to("tack:thought-enter?name=po-epic-work-focus&maxTokens=500")
                .to("direct:po-epic-work-loop")
                .to("direct:po-product-awareness")
                .to("tack:thought-exit?name=po-epic-work-focus");

        from("direct:po-epic-work-loop")
                .routeId("po-epic-work-loop")
                .setProperty("poEpicWorkIterations", constant(0))
                .loopDoWhile(and(exchangeProperty("poEpicWorkIterations").isLessThan(6),
                        or(exchangeProperty("poEpicWorkIterations").isEqualTo(0),
                            exchangeProperty("act.po-epic-work.durableProgress").isEqualTo(true))))
                    .setProperty("poEpicWorkIterations",
                            simple("${exchangeProperty.poEpicWorkIterations}++", Integer.class))
                    .to("direct:po-epic-definition")
                .end();

        from("direct:po-epic-definition")
                .routeId("po-epic-definition")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Advance one independently valuable Epic. Define observable Given/When/Then acceptance and product
                        priority. Create it planned, classify with epic, and assign yourself before promoting it to to-do.
                        Product-ready to-do needs no repository setup, decomposition, staffing or chat. Maintain a deeper
                        to-do queue and normally 2–3 in-progress Epics. Activate a to-do Epic when its verified assigned,
                        executable first Story is to-do, in-progress, in-review, or done and downstream capacity supports
                        delivery. Keep product acceptance separate from source review.
                        Refine to-do Epics while the active set is full. A defect-driven reopen can exceed the active target.
                        PO defines product behavior. PM owns Story decomposition and engineering routing.
                        """))
                .to("tack:act?id=po-epic-work&toolProfile=" + ProductOwnershipToolProfile.ID);

        from("direct:po-focus-defect-triage")
                .routeId("po-focus-defect-triage")
                .to("direct:po-defect-triage");

        from("direct:po-defect-triage")
                .routeId("po-defect-triage")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant(
                        "Exact Defect, Test and Epic locators, observed behavior, product disposition and repair handoff."))
                .to("tack:thought-enter?name=po-defect-triage-focus&maxTokens=400")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Triage one planned QA Defect using the originating Test via its related relation, reproduction evidence,
                        and candidate Epic acceptance. If validated, add a subtask relation from the Epic to the Defect, set
                        product priority, and comment whether it blocks acceptance and why. An acceptance-blocking Defect goes
                        to-do for PM routing. Reopen an affected done Epic first. Defer other validated Defects as planned
                        when appropriate. Leave repair assignment to PM.
                        If the claim is invalid or duplicate, comment with the disposition and archive it. Clarify Epic
                        acceptance when needed and request retest on the originating Test. QA owns Test lifecycle.
                        """))
                .to("tack:act?id=po-defect-triage&toolProfile=" + ProductOwnershipToolProfile.ID)
                .to("direct:po-product-awareness")
                .to("tack:thought-exit?name=po-defect-triage-focus");

        from("direct:po-focus-acceptance")
                .routeId("po-focus-acceptance")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Focused acceptance for one active Epic: exact Gherkin, successful delivery/test evidence, open
                        defects, QA behavioral proof, product gaps, and the final Epic lifecycle decision. Keep exact Epic,
                        Story, Test and Defect IDs and evidence locators.
                        """))
                .to("tack:thought-enter?name=po-acceptance-focus&maxTokens=500")
                .to("direct:po-epic-acceptance")
                .to("direct:po-product-awareness")
                .to("tack:thought-exit?name=po-acceptance-focus");

        from("direct:po-epic-acceptance")
                .routeId("po-epic-acceptance")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Accept one in-progress Epic only when current QA evidence demonstrates its Gherkin, required Story
                        and Test children are done, and validated acceptance-blocking Defects are resolved with passing evidence.
                        Read subtask relations and the recorded product disposition. Deferred nonblocking Defects may remain
                        planned. Archived work does not count as success. If evidence is insufficient, leave the Epic
                        in-progress and record the exact gap. Otherwise record acceptance evidence and move it to done.
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
