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
                        Mission anchor and verified Kaneo workspace ID, project ID/slug, PO user ID and Mattermost team/channel IDs.
                        Keep Epic/Defect IDs or tickets, Gherkin intent, acceptance evidence, and the next actionable product condition.
                        """))
                .to("tack:thought-enter?name=po-root&maxTokens=450")
                .setProperty("decision.product-owner-mission-complete.result", constant("no"))
                .loopDoWhile(exchangeProperty("decision.product-owner-mission-complete.result").isNotEqualTo("yes"))
                    .to("direct:po-orientation")
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Choose the highest-value Product Owner focus.
                            - epic-work: define, own and prioritize missing product outcomes, finish planned Epic staging, or activate a
                              product-ready Epic with prepared Stories and downstream capacity. Definition and to-do need no delivery setup.
                            - defect-triage: a planned Defect is awaiting product disposition.
                            - acceptance: an active Epic has new evidence for acceptance.
                            - qa-feedback: QA needs a product clarification.
                            - viability: a scope or value question needs Director judgment.
                            - coordination: a fresh product question needs a response.
                            - complete: all required outcomes are accepted and done with product blockers resolved.
                            - wait: the next condition belongs to another role or new evidence is needed.
                            """))
                    .to("tack:decide?id=po-focus&choices=defect-triage,acceptance,epic-work,qa-feedback,viability,coordination,complete,wait")
                    .toD("direct:po-focus-${exchangeProperty.decision.po-focus.result}")
                .end()
                .end()
                .to("tack:thought-exit?name=po-root");

        from("direct:po-orientation")
                .routeId("po-orientation")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Find the mission's Epic coverage, current product-ready runway, and untriaged Defects. Record exact
                        workspace, project and PO user locators. Read Story or QA evidence only for activation or acceptance.
                        """))
                .to("tack:act?id=po-orientation&toolProfile=" + WorkspaceReadonlyToolProfile.ID);

        from("direct:po-focus-epic-work")
                .routeId("po-focus-epic-work")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Exact project, PO user and selected Epic IDs. Track definition, classification, assignment and product readiness.
                        Keep delivery activation evidence separate.
                        """))
                .to("tack:thought-enter?name=po-epic-work-focus&maxTokens=500")
                .to("direct:po-epic-work-loop")
                .to("tack:thought-exit?name=po-epic-work-focus")
                .to("direct:po-product-awareness");

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
                        Advance one independently valuable Epic. Resume a matching planned task, or create it planned.
                        Define Given/When/Then acceptance, set priority, attach epic and assign yourself. Verify the label
                        and assignment before promoting to to-do
                        as soon as it is product-ready. This work proceeds independently of repositories, Stories, staffing and chat.
                        Move a to-do Epic to in-progress only when PM has prepared Stories and downstream capacity supports delivery.
                        Keep the active set shallow (normally 2–3) with a deeper to-do runway. Blocking defects may reopen done Epics.
                        """))
                .to("tack:act?id=po-epic-work&toolProfile=" + ProductOwnershipToolProfile.ID);

        from("direct:po-focus-defect-triage")
                .routeId("po-focus-defect-triage")
                .to("direct:po-defect-triage");

        from("direct:po-defect-triage")
                .routeId("po-defect-triage")
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
                .to("direct:po-product-awareness");

        from("direct:po-focus-acceptance")
                .routeId("po-focus-acceptance")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Exact Epic, related Dev/QA tasks and PR locators needed for acceptance. Keep Gherkin, proof, unresolved
                        defects and the decision or next evidence needed.
                        """))
                .to("tack:thought-enter?name=po-acceptance-focus&maxTokens=500")
                .to("direct:po-epic-acceptance")
                .to("tack:thought-exit?name=po-acceptance-focus")
                .to("direct:po-product-awareness");

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
