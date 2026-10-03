package us.nusun.llmshare.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.example.tool.ArchitectSourceControlWriteToolProfile;
import us.nusun.llmshare.example.tool.EngineeringWorkToolProfile;
import us.nusun.llmshare.example.tool.IntegrationToolProfile;
import us.nusun.llmshare.example.tool.TeamChatToolProfile;
import us.nusun.llmshare.example.tool.WorkspaceReadonlyToolProfile;
import us.nusun.llmshare.framework.inventory.RoutineProvider;
import us.nusun.llmshare.framework.model.LlmPassType;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.tack.TackHeaders;

import java.util.List;

@ApplicationScoped
public class ArchitectRoutine extends RouteBuilder implements RoutineProvider {
    public static final String ID = "software-team/architect";

    @Override
    public List<RoutineDefinition> routines() {
        return List.of(routine());
    }

    public RoutineDefinition routine() {
        return RoutineDefinition.builder(ID)
                .name("Architect")
                .main("Make architecture concrete through repositories, implementation, and integration.",
                        "direct:example-software-team-architect")
                .subroutine("repository-architecture", "Establish the system and component repository model.",
                        "direct:architect-repository-architecture")
                .subroutine("integration", "Resolve and integrate system/component pull requests.",
                        "direct:architect-integration")
                .subroutine("implementation", "Execute assigned architecture-heavy engineering work.",
                        "direct:architect-implementation")
                .defaultLane("high-pass")
                .withLaneBinding(LlmPassType.SYNTHESIS, "low-pass")
                .withLaneBinding(LlmPassType.CONSOLIDATION, "low-pass")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-software-team-architect")
                .routeId("example-software-team-architect")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Durable architecture anchors: system repo, component repo map, architecture decisions,
                        assigned ARCH work, open PRs, conflicts, integration blockers, and cross-role questions.
                        """))
                .to("tack:thought-enter?name=architect-root&maxTokens=450")
                .setProperty("decision.architect-mission-complete.result", constant("no"))
                .loopDoWhile(exchangeProperty("decision.architect-mission-complete.result").isNotEqualTo("yes"))
                    .to("direct:architect-orientation")
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Choose exactly one highest-value Architect focus.
                            - repository-architecture: system repo/component repo model is missing, incomplete, or
                              inconsistent with the current architecture.
                            - reconcile: an ARCH-assigned work:implementation Story remains In Progress after its linked
                              PR is verified merged; Architect owns its transition to Done.
                            - integration: an open system/component PR can be merged, needs conflict recovery, or needs a
                              small integration adjustment.
                            - implementation: an assigned ARCH work:implementation Story or work:defect Bug is actionable.
                            - coordination: a fresh architecture/product/QA/developer question needs Architect judgment.
                            - wait: none of the above is useful now.
                            Prefer concrete implementation/integration over passive review. Choose exactly one value.
                            """))
                    .to("tack:decide?id=architect-focus&choices=repository-architecture,reconcile,integration,implementation,coordination,wait")
                    .toD("direct:architect-focus-${exchangeProperty.decision.architect-focus.result}")
                .end()
                .end()
                .to("tack:thought-exit?name=architect-root");

        from("direct:architect-orientation")
                .routeId("architect-orientation")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read the smallest current-mission slice needed to rank Architect focus. Identify exactly one
                        system repository and the component repository set documented by current architecture; active
                        Epic context; ARCH-assigned work:implementation/work:defect; open PRs from system/components;
                        mergeability/conflicts when relevant; one cross-repo blocker; one fresh architecture exchange.
                        The system repo owns GitOps, system docs, and system behavior tests. Component repos contain
                        deployable application components. Report observations only. Do not mutate.
                        """))
                .to("tack:act?id=architect-orientation&toolProfile=" + WorkspaceReadonlyToolProfile.ID);

        from("direct:architect-focus-repository-architecture")
                .routeId("architect-focus-repository-architecture")
                .to("direct:architect-repository-architecture");

        from("direct:architect-repository-architecture")
                .routeId("architect-repository-architecture")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Make one concrete repository-architecture advance. Reuse current mission repositories when their
                        responsibility is already clear. Otherwise provision only what the architecture requires:
                        exactly one system repository and the justified component repository set. Establish the system
                        repository baseline with README.md, gitops/README.md, docs/architecture.md, and
                        tests/system/README.md so GitOps, system documentation, and behavioral-test ownership are explicit.
                        Document the component repository map and boundaries in system docs. Initialize component repos
                        with a minimal README/buildable baseline appropriate to the architecture, not speculative features.
                        Do not create a separate docs or QA repository. Avoid duplicate/near-duplicate repositories.
                        """))
                .to("tack:act?id=architect-repository-step&toolProfile="
                        + ArchitectSourceControlWriteToolProfile.ID)
                .to("direct:architect-awareness");

        from("direct:architect-focus-reconcile")
                .routeId("architect-focus-reconcile")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Reconcile exactly one ARCH-assigned work:implementation Story whose linked PR is verified merged.
                        Confirm the Story is still In Progress, the PR/commit actually merged to the intended base, and no
                        newer evidence requires source changes. Move that Story to Done with concise merge/validation
                        evidence. Never close work:defect here; repaired Bugs remain In Progress for QA retest/closure.
                        """))
                .to("tack:act?id=architect-story-reconcile&toolProfile=" + EngineeringWorkToolProfile.ID)
                .to("direct:architect-awareness");

        from("direct:architect-focus-implementation")
                .routeId("architect-focus-implementation")
                .to("direct:architect-implementation");

        from("direct:architect-implementation")
                .routeId("architect-implementation")
                .setProperty("decision.architect-has-work.result", constant("no"))
                .setHeader(TackHeaders.PROMPT, constant("""
                        Select exactly one ARCH-assigned actionable work:implementation Story or work:defect Bug.
                        Prefer assigned In Progress; otherwise claim assigned Todo -> In Progress. Verify classification,
                        Epic parent/acceptance, repository locator, dependencies, and expected validation. Never self-assign
                        unowned backlog work. Ordinary component stories assigned to DEV remain DEV-owned.
                        """))
                .to("tack:act?id=architect-work-select&toolProfile=" + EngineeringWorkToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Is one valid ARCH-assigned engineering unit now selected In Progress with enough context to
                        implement? Answer yes or no.
                        """))
                .to("tack:decide?id=architect-has-work")
                .choice()
                    .when(exchangeProperty("decision.architect-has-work.result").isEqualTo("yes"))
                        .setHeader(TackHeaders.PROMPT, constant("""
                                Implement exactly one coherent architecture-owned patch in the existing system/component
                                repository: integration/system wiring, GitOps, repository structure, cross-component
                                contract, architecture-heavy component work, or assigned defect repair. Reuse the task
                                branch/PR when continuing. Validate directly in the sandbox. Do not create a new repository
                                to solve an ordinary implementation task, and do not absorb unrelated DEV work.
                                """))
                        .to("tack:act?id=architect-implementation-step&toolProfile="
                                + ArchitectSourceControlWriteToolProfile.ID)
                        .setHeader(TackHeaders.PROMPT, constant("""
                                Update only the selected Plane work with branch/commit/PR, changed scope, validation, and
                                concrete blockers. Keep work:implementation In Progress while its PR is open; Architect
                                reconciles its assigned Story Done after verified merge. Keep work:defect In Progress
                                through merge; QA retest closes the Bug.
                                """))
                        .to("tack:act?id=architect-work-evidence&toolProfile=" + EngineeringWorkToolProfile.ID)
                        .to("direct:architect-awareness")
                .end();

        from("direct:architect-focus-integration")
                .routeId("architect-focus-integration")
                .to("direct:architect-integration");

        from("direct:architect-integration")
                .routeId("architect-integration")
                .setProperty("decision.architect-has-integration-candidate.result", constant("no"))
                .setProperty("decision.architect-needs-conflict-recovery.result", constant("no"))
                .setHeader(TackHeaders.PROMPT, constant("""
                        Select one current open PR tied to active mission work that is a useful integration candidate.
                        Capture exact repo/PR, linked Story/Bug/work:test Task, Epic acceptance scope, head/base,
                        validation evidence, mergeability/conflict state, and current blocker/review evidence. QA test-code
                        PRs in the system repository are first-class integration candidates. Prefer oldest safe integration
                        WIP before newer work. Do not require approval phrases or chat handoffs.
                        """))
                .to("tack:act?id=architect-integration-select&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Is one exact open PR selected with enough linked work/acceptance context for integration handling?
                        Answer yes or no.
                        """))
                .to("tack:decide?id=architect-has-integration-candidate")
                .choice()
                    .when(exchangeProperty("decision.architect-has-integration-candidate.result").isEqualTo("yes"))
                        .setHeader(TackHeaders.PROMPT, constant("""
                                Does the selected PR require source-level conflict recovery or a small integration patch
                                before it can receive a final merge check? Answer yes only for current concrete source
                                evidence, not speculation.
                                """))
                        .to("tack:decide?id=architect-needs-conflict-recovery")
                        .choice()
                            .when(exchangeProperty("decision.architect-needs-conflict-recovery.result").isEqualTo("yes"))
                                .setHeader(TackHeaders.PROMPT, constant("""
                                        Recover the existing selected branch/PR against the current intended base.
                                        Resolve only the concrete conflicts/integration incompatibility, preserve product
                                        behavior, rerun relevant validation, commit/push to the same branch, and reuse the
                                        same PR. If resolution requires a product decision, do not guess; stop with the
                                        exact question for PO.
                                        """))
                                .to("tack:act?id=architect-conflict-recovery&toolProfile="
                                        + ArchitectSourceControlWriteToolProfile.ID)
                        .end()
                        .setHeader(TackHeaders.PROMPT, constant("""
                                Perform the final merge decision for the selected PR using fresh current evidence.
                                Merge only when the PR is still open, targets the intended base, is mergeable/non-conflicted,
                                validation is credible for linked work, acceptance scope is satisfied, and no current
                                blocker/product ambiguity contradicts merge. For work:test, validate that the change belongs
                                in the system repository and advances the linked Epic evidence without redefining product
                                intent. Never require a magic PM/QA approval comment. If unsafe or ambiguous, do not merge.
                                """))
                        .to("tack:act?id=architect-final-integration&toolProfile=" + IntegrationToolProfile.ID)
                        .to("direct:architect-awareness")
                .end();

        from("direct:architect-focus-coordination")
                .routeId("architect-focus-coordination")
                .to("direct:architect-awareness");

        from("direct:architect-awareness")
                .routeId("architect-awareness")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Send/reply once only when repository architecture, integration result, conflict, product question,
                        QA finding, or DEV unblock will help another routine act sooner. Avoid generic status.
                        """))
                .to("tack:act?id=architect-awareness&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:architect-focus-wait")
                .routeId("architect-focus-wait")
                .setHeader(TackHeaders.PROMPT, constant("""
                        May the Architect routine end for this mission? Answer yes only when every required product
                        Epic is terminal, the system/component repository model is established, no ARCH-assigned
                        engineering or integration remains, and no architecture blocker remains. Temporary absence of
                        Architect work while any required Epic is nonterminal is idle time, not routine completion;
                        remain active for integration, conflicts, and the next architecture-heavy slice.
                        """))
                .to("tack:decide?id=architect-mission-complete")
                .choice()
                    .when(exchangeProperty("decision.architect-mission-complete.result").isNotEqualTo("yes"))
                        .delay(120000)
                .end();
    }
}
