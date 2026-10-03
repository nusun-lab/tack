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
                .main("Own product Epics from definition through evidence-based acceptance.",
                        "direct:example-software-team-product-owner")
                .subroutine("epic-definition", "Define and prioritize product Epics.", "direct:po-epic-definition")
                .subroutine("epic-acceptance", "Accept an Epic using current completion evidence.",
                        "direct:po-epic-acceptance")
                .subroutine("defect-triage", "Triage QA defects into product priority and Epic context.",
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
                        Durable product anchors: mission value, Epic type id, prioritized Epics, active Epic,
                        acceptance intent, QA evidence, open defects, product questions, and viability findings.
                        """))
                .to("tack:thought-enter?name=po-root&maxTokens=450")
                .setProperty("decision.product-owner-mission-complete.result", constant("no"))
                .loopDoWhile(exchangeProperty("decision.product-owner-mission-complete.result").isNotEqualTo("yes"))
                    .to("direct:po-orientation")
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Choose exactly one highest-value Product Owner focus.
                            - defect-triage: an open QA work:defect Bug needs Epic association, product priority,
                              acceptance clarification, or confirmation that it is not a product defect.
                            - acceptance: the active Epic has implementation and QA evidence sufficient for a product
                              acceptance decision.
                            - epic-work: required outcomes are missing/weak, no Epic is active, or the ready Epic backlog
                              is too shallow for foreseeable mission scope.
                            - qa-feedback: QA raised an acceptance ambiguity or evidence question requiring product judgment.
                            - viability: evidence suggests product scope/value or mission interpretation needs Director input.
                            - coordination: another fresh product question needs a concise response.
                            - wait: none of the above is useful now.
                            Normally keep exactly one Epic In Progress. Choose exactly one value.
                            """))
                    .to("tack:decide?id=po-focus&choices=defect-triage,acceptance,epic-work,qa-feedback,viability,coordination,wait")
                    .toD("direct:po-focus-${exchangeProperty.decision.po-focus.result}")
                .end()
                .end()
                .to("tack:thought-exit?name=po-root");

        from("direct:po-orientation")
                .routeId("po-orientation")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read the smallest current-mission slice needed to rank Product Owner focus. Resolve/read the
                        project Epic work-item type and identify typed Epics using the exact resolved type id.
                        Capture: active In Progress Epic count; ready Todo Epic depth; missing mission outcomes; current
                        active Epic acceptance intent; implementation/test child progress when relevant; open
                        work:defect Bugs; QA evidence/ambiguity; one fresh product exchange; one viability concern.
                        A sparse work-item list may omit parent; retrieve only a selected child when its Epic parent is
                        needed. Report observations only. Do not mutate.
                        """))
                .to("tack:act?id=po-orientation&toolProfile=" + WorkspaceReadonlyToolProfile.ID);

        from("direct:po-focus-epic-work")
                .routeId("po-focus-epic-work")
                .to("direct:po-epic-definition");

        from("direct:po-epic-definition")
                .routeId("po-epic-definition")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Make one coherent Epic lifecycle advance. Resolve the Epic work-item type for the mission project.
                        Product outcomes are work items of that exact type. Create/refine only independently valuable
                        outcomes supported by the mission, with observable acceptance behavior and useful product priority.
                        Keep future outcomes Backlog until sufficiently defined; use Todo for ready product work.
                        Normally keep exactly one Epic In Progress. If none is active, promote the highest-value ready
                        Todo Epic to In Progress. Start a second Epic only when the current one is externally blocked and
                        parallel product work is explicitly justified in evidence. Maintain a small ready backlog without
                        expanding active WIP. Do not create Story implementation work, Task test work, or repair ownership.
                        """))
                .to("tack:act?id=po-epic-work&toolProfile=" + ProductOwnershipToolProfile.ID)
                .to("direct:po-product-awareness");

        from("direct:po-focus-defect-triage")
                .routeId("po-focus-defect-triage")
                .to("direct:po-defect-triage");

        from("direct:po-defect-triage")
                .routeId("po-defect-triage")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Triage one open QA-created work:defect Bug. Read its reproducible evidence and candidate Epic
                        context. If it represents a product behavior failure, classify it by setting its parent to the
                        correct affected Epic and set product priority that reflects user/value impact. If the defect
                        proves a previously Done Epic no longer satisfies its accepted behavior, move that Epic back to
                        In Progress before repair routing. Clarify Epic acceptance intent when the failure exposes
                        ambiguity. Leave repair assignment to PM. If evidence instead proves a test
                        defect, pure product ambiguity, or duplicate finding, record that disposition and Cancel the Bug
                        only when the product defect truly should not proceed. Do not create a replacement repair item.
                        """))
                .to("tack:act?id=po-defect-triage&toolProfile=" + ProductOwnershipToolProfile.ID)
                .to("direct:po-product-awareness");

        from("direct:po-focus-acceptance")
                .routeId("po-focus-acceptance")
                .to("direct:po-epic-acceptance");

        from("direct:po-epic-acceptance")
                .routeId("po-epic-acceptance")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Decide acceptance for the one active Epic. Read the Epic plus only its child work/evidence needed
                        for the decision. Move the Epic to Done only when its promised behavior is demonstrated by current
                        QA evidence, required delivery/test children are terminal, and no relevant work:defect Bug remains
                        open. A merged PR alone is not acceptance. If evidence is insufficient, keep the Epic In Progress
                        and record the exact missing product/evidence condition without creating implementation work.
                        """))
                .to("tack:act?id=po-epic-acceptance&toolProfile=" + ProductOwnershipToolProfile.ID)
                .to("direct:po-product-awareness");

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
                        Send/reply once only when an Epic lifecycle change, defect triage, acceptance decision, product
                        clarification, or viability question will help PM, QA, Architect, Developer, or Director act
                        sooner. Avoid generic status.
                        """))
                .to("tack:act?id=po-product-awareness&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:po-focus-wait")
                .routeId("po-focus-wait")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Is Product Owner work complete? Answer yes only when all required mission outcomes are represented
                        by typed Epics, every required Epic is accepted Done, and no unresolved product/defect/viability
                        question blocks mission completion.
                        """))
                .to("tack:decide?id=product-owner-mission-complete")
                .choice()
                    .when(exchangeProperty("decision.product-owner-mission-complete.result").isNotEqualTo("yes"))
                        .delay(120000)
                .end();
    }
}
