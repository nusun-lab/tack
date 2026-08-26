package us.nusun.llmshare.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.example.tool.ProjectManagementToolProfile;
import us.nusun.llmshare.example.tool.SourceControlToolProfile;
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
                        move delivery-ready product slices from Backlog to Todo, and accept completed In Progress product
                        outcomes only when durable evidence satisfies their existing acceptance criteria. Product slices describe
                        user outcomes; implementation decomposition belongs to the Project Manager.
                        """, "direct:example-software-team-product-owner")
                .subroutine(
                        "acceptance-refinement",
                        "Refine one Backlog product slice into a small user outcome with clear scope and testable Gherkin acceptance.",
                        "direct:example-software-team-product-owner-acceptance-refinement")
                .subroutine(
                        "priority-handoff",
                        "Move one ready product slice Backlog -> Todo for delivery planning, or accept/reject one completed In Progress product outcome.",
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
                    .to("direct:po-project-basics-check")
                    .choice()
                        .when(exchangeProperty("decision.product-owner-has-project-basics.result").isEqualTo(true))
                            .to("direct:po-product-cycle")
                        .otherwise()
                            .to("direct:po-missing-basics-closeout")
                    .end()
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Review current mission completion status once using the smallest authoritative read.
                                    One verified unfinished user-facing outcome is enough to conclude not complete.
                                    Plane state is authoritative. Do not mutate anything in this status check.
                                    """))
                    .to("tack:act?id=po-mission-status-review&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is the entire mission complete from the product-ownership perspective?
                                    Return true only when every required user-facing outcome is represented and accepted Done
                                    against its intended acceptance criteria, with no remaining product decision or acceptance
                                    follow-up. Todo or In Progress product work means the mission is not complete.
                                    """))
                    .to("tack:decide?id=product-owner-mission-complete")
                    .filter(exchangeProperty("decision.product-owner-mission-complete.result").isNotEqualTo(true))
                        .delay(120000)
                    .end()
                .end()
                .end()
                .to("direct:po-product-owner-root-exit");

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
                        product documentation, current-work page, project-management workspace, and source repository.
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
                .setHeader(TackHeaders.PROMPT, constant("""
                                Are project basics present and linked enough to do product work?
                                """))
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
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Current product work unit: one freshly selected product slice, its real Plane state, product scope/Gherkin,
                        delivery or acceptance handoff, and the next distinct product outcome when more work remains.
                        """))
                    .to("tack:thought-enter?name=po-cycle&maxTokens=300")
                    .to("direct:po-select-product-work")
                    .choice()
                        .when(exchangeProperty("decision.product-owner-has-work.result").isEqualTo(true))
                            .to("direct:po-acceptance-work")
                            .choice()
                                .when(exchangeProperty("act.po-canonical-acceptance-update.durableProgress").isEqualTo(true))
                                    .setProperty("poMadeProgress", constant(true))
                            .endChoice()
                            .to("direct:po-plane-state-handoff")
                            .choice()
                                .when(exchangeProperty("act.po-plane-handoff-update.durableProgress").isEqualTo(true))
                                    .setProperty("poMadeProgress", constant(true))
                            .endChoice()
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
                        Product selection: one product parent only, never an implementation child. Prefer acceptance work,
                        then one Backlog user outcome needing refinement, then create a missing product outcome only when
                        the mission has an unrepresented user-facing outcome.
                        """))
                .to("tack:thought-enter?name=po-selection&maxTokens=250")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Using fresh Plane evidence, select exactly one PRODUCT PARENT, never an implementation child.

                                First prefer an In Progress product parent whose implementation children are Done and whose
                                latest PM PRODUCT ACCEPTANCE READY handoff is newer than any PRODUCT ACCEPTANCE FEEDBACK on
                                that parent. A newer PRODUCT ACCEPTANCE FEEDBACK invalidates every older READY handoff; while
                                such feedback is unresolved, leave the parent to delivery and do not re-accept it.

                                Otherwise select one Backlog product slice needing product definition. If no suitable Backlog
                                slice exists and a required mission user outcome is genuinely unrepresented, create one Backlog
                                product parent.

                                Product parents describe observable user value and acceptance. They are not developer tasks.
                                Do not create implementation children, assign developers, describe code patches, or reselect an
                                unchanged Todo product slice already handed to delivery unless a product question was explicitly
                                returned to you. Stickiness never justifies inventing additional product scope.
                                """))
                .to("tack:act?id=po-backlog-review&toolProfile=" + ProjectManagementToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                                Is exactly one current product parent selected for useful Product Owner work now?
                                Return true only for a selected Backlog product parent needing definition/handoff, or an
                                In Progress product parent with a current PRODUCT ACCEPTANCE READY newer than any product
                                acceptance feedback. Return false when nothing suitable was selected or created, including
                                when remaining product parents are already Todo and owned by delivery with no product question returned.
                                """))
                .to("tack:decide?id=product-owner-has-work")
                .choice()
                    .when(exchangeProperty("decision.product-owner-has-work.result").isEqualTo(true))
                        .setHeader(TackHeaders.PROMPT, constant("""
                                        Read only the selected product page, current-work navigation, direct completion evidence,
                                        and the selected parent's current PRODUCT ACCEPTANCE READY / PRODUCT ACCEPTANCE FEEDBACK
                                        ordering. Do not read the whole documentation repository.
                                        """))
                        .to("tack:act?id=po-product-context-read&toolProfile=" + SourceControlToolProfile.ID)
                .end()
                .to("tack:thought-exit?name=po-selection");

        from("direct:po-acceptance-work")
                .routeId("po-acceptance-work")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Selected product parent: user value, in/out scope, existing Gherkin, Plane state, completion
                        evidence when acceptance is requested, and one exact unmet outcome when acceptance fails.
                        """))
                .to("tack:thought-enter?name=po-acceptance&maxTokens=250")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Curate or evaluate exactly one compact canonical product page for the selected product parent.
                                For Backlog product work, define one coherent user-facing outcome with value, scope boundaries,
                                and compact observable Given/When/Then acceptance. Keep technical decomposition out of this page.

                                For an In Progress product parent awaiting acceptance, proceed only when the current PM
                                PRODUCT ACCEPTANCE READY handoff is newer than any PRODUCT ACCEPTANCE FEEDBACK on the parent.
                                Preserve the existing scope and Gherkin. Evaluate only durable completion evidence supplied by
                                delivery. Record which existing scenarios are satisfied or one exact unmet scenario. Never
                                rewrite acceptance criteria to fit implementation. If required evidence is missing, record the
                                gap and stop rather than searching for substitute evidence.
                                """))
                .to("tack:act?id=po-canonical-acceptance-update&toolProfile=" + SourceControlWriteToolProfile.ID)
                .to("tack:thought-exit?name=po-acceptance");

        from("direct:po-plane-state-handoff")
                .routeId("po-plane-state-handoff")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Update only the selected PRODUCT PARENT using Plane's real states:
                                Backlog, Todo, In Progress, Done, Cancelled.

                                For a Backlog product slice whose user scope and Gherkin acceptance are usable, move it
                                Backlog -> Todo. This state transition is the authoritative handoff to Project Management.
                                Add one concise PRODUCT HANDOFF comment with the canonical acceptance page, priority, open
                                product questions if any, and requested delivery outcome. PM must not need the comment to
                                discover eligibility; Todo is authoritative.

                                For an In Progress product parent awaiting acceptance, move it In Progress -> Done only when
                                the current PM PRODUCT ACCEPTANCE READY handoff is newer than any PRODUCT ACCEPTANCE FEEDBACK
                                and durable evidence satisfies every existing Gherkin scenario. If acceptance fails, keep it
                                In Progress and add or refresh one PRODUCT ACCEPTANCE FEEDBACK comment naming the exact unmet
                                existing scenario and requested product outcome. That new feedback immediately invalidates the
                                READY handoff that triggered this acceptance pass. Do not move rejected work back to Backlog
                                or Todo; delivery is already in progress.

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
