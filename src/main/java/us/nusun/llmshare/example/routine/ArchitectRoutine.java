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
                            - reconcile: an ARCH-assigned story remains In Progress after its linked
                              PR is verified merged; Architect owns its transition to Done.
                            - integration: any current-mission open PR can be safely merged, needs conflict recovery, or
                              needs a small integration adjustment. Architect owns and drains the whole integration queue,
                              including Architect-authored PRs. Safe integration work outranks new ARCH implementation work.
                            - implementation: an assigned, classification-verified ARCH story or defect is actionable
                              and no safe integration candidate is waiting.
                            - coordination: a fresh architecture/product/QA/developer question needs Architect judgment.
                            - complete: every required product Epic is terminal, the repository model is established, no
                              ARCH-assigned engineering or integration remains, and no architecture blocker remains.
                            - wait: the mission is not complete and none of the other focuses is useful now.
                            Prefer concrete implementation/integration over passive review. Choose exactly one value.
                            """))
                    .to("tack:decide?id=architect-focus&choices=repository-architecture,reconcile,integration,implementation,coordination,complete,wait")
                    .toD("direct:architect-focus-${exchangeProperty.decision.architect-focus.result}")
                .end()
                .end()
                .to("tack:thought-exit?name=architect-root");

        from("direct:architect-orientation")
                .routeId("architect-orientation")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read only enough fresh current-mission state to rank Architect focus. Reuse the verified
                        agent-workspace repository map already in thought. Check: whether repository architecture is
                        still missing; any ARCH-assigned classification-verified source work needing reconciliation;
                        current open PRs across the known mission repos regardless of author; and actionable ARCH-assigned
                        Story/Defect work. Do not manufacture a blocker or reread chat/repository structure when existing
                        anchors remain valid. Integration candidates outrank new implementation. Report only; do not mutate.
                        """))
                .to("tack:act?id=architect-orientation&toolProfile=" + WorkspaceReadonlyToolProfile.ID);

        from("direct:architect-focus-repository-architecture")
                .routeId("architect-focus-repository-architecture")
                .to("direct:architect-repository-architecture");

        from("direct:architect-repository-architecture")
                .routeId("architect-repository-architecture")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Make one concrete repository-architecture advance. All mission repositories belong under the
                        agent-workspace Gitea organization; repositories in a persona's personal namespace are not valid
                        mission repositories. Reuse current mission repositories under agent-workspace when their
                        responsibility is already clear. Otherwise provision only what the architecture requires:
                        exactly one system repository and the justified component repository set. Establish the system
                        repository baseline with README.md, gitops/README.md, docs/architecture.md, and
                        tests/system/README.md so GitOps, system documentation, and behavioral-test ownership are explicit.
                        Document the component repository map and boundaries in system docs. Initialize component repos
                        with a minimal README/buildable baseline appropriate to the architecture, not speculative features.
                        Prefer <anchor>-service for deployable components that implement/expose APIs. Reserve <anchor>-api
                        for repositories whose primary responsibility is an API specification/contract such as OpenAPI or
                        AsyncAPI, so runtime services do not collide with future API-spec golden paths.
                        Do not create a separate docs or QA repository. Avoid duplicate/near-duplicate repositories.
                        """))
                .to("tack:act?id=architect-repository-step&toolProfile="
                        + ArchitectSourceControlWriteToolProfile.ID)
                .to("direct:architect-awareness");

        from("direct:architect-focus-reconcile")
                .routeId("architect-focus-reconcile")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Reconcile exactly one ARCH-assigned story whose linked PR is verified merged.
                        Confirm the Story is still In Progress, the PR/commit actually merged to the intended base, and no
                        newer evidence requires source changes. Move that Story to Done with concise merge/validation
                        evidence. Never close defect here; repaired Defects remain In Progress for QA retest/closure.
                        """))
                .to("tack:act?id=architect-story-reconcile&toolProfile=" + EngineeringWorkToolProfile.ID)
                .to("direct:architect-awareness");

        from("direct:architect-focus-implementation")
                .routeId("architect-focus-implementation")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        One architecture-owned engineering unit: exact Plane id/classification/owner, Epic acceptance,
                        repository locator, branch/PR, dependency state, validation, and current blocker.
                        """))
                .to("tack:thought-enter?name=architect-implementation-focus&maxTokens=500")
                .to("direct:architect-implementation")
                .to("tack:thought-exit?name=architect-implementation-focus")
                .to("direct:architect-awareness");

        from("direct:architect-implementation")
                .routeId("architect-implementation")
                .setProperty("decision.architect-has-work.result", constant("no"))
                .setHeader(TackHeaders.PROMPT, constant("""
                        Select exactly one ARCH-assigned actionable engineering unit. Before any lifecycle mutation,
                        verify attached classification in current evidence: story for delivery or defect for repair.
                        Unknown/missing classification means do not mutate. A epic
                        or test is never claimable ARCH source work. Prefer assigned In Progress; otherwise claim a
                        classification-verified assigned Todo -> In Progress. For a story, verify its
                        parent Epic is In Progress before claiming it; Todo/Backlog Epic children are prepared future work.
                        Verify Epic parent/acceptance, exact agent-workspace repository locator, dependencies, and expected
                        validation. Never self-assign
                        unowned Backlog work. Ordinary component Stories assigned to DEV remain DEV-owned.
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
                                concrete blockers. Keep story In Progress while its PR is open; Architect
                                reconciles its assigned Story Done after verified merge. Keep defect In Progress
                                through merge; QA retest closes the Defect.
                                """))
                        .to("tack:act?id=architect-work-evidence&toolProfile=" + EngineeringWorkToolProfile.ID)
                .end();

        from("direct:architect-focus-integration")
                .routeId("architect-focus-integration")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Drain the current integration queue. Preserve exact selected Gitea owner/repo/PR, linked Plane
                        work, head/base, mergeability, validation evidence, and any concrete blocker across selection,
                        conflict recovery, and final merge. Exact locators are the most important memory in this scope.
                        """))
                .to("tack:thought-enter?name=architect-integration-focus&maxTokens=550")
                .to("direct:architect-integration-focus")
                .to("tack:thought-exit?name=architect-integration-focus")
                .to("direct:architect-awareness");

        from("direct:architect-integration-focus")
                .routeId("architect-integration-focus")
                .setProperty("architectIntegrationIterations", constant(0))
                .setProperty("architectIntegrationContinue", constant(true))
                .loopDoWhile(simple("${exchangeProperty.architectIntegrationContinue} == true && ${exchangeProperty.architectIntegrationIterations} < 4"))
                    .setProperty("architectIntegrationIterations", simple("${exchangeProperty.architectIntegrationIterations++}"))
                    .setProperty("architectIntegrationContinue", constant(false))
                    .to("direct:architect-integration")
                    .choice()
                        .when(simple("${exchangeProperty.act.architect-integration-batch.durableProgress} == true || "
                                + "${exchangeProperty.act.architect-conflict-recovery.durableProgress} == true"))
                            .setProperty("architectIntegrationContinue", constant(true))
                    .end()
                .end();

        from("direct:architect-integration")
                .routeId("architect-integration")
                .setProperty("decision.architect-needs-conflict-recovery.result", constant("no"))
                .setProperty("act.architect-integration-batch.durableProgress", constant(false))
                .setHeader(TackHeaders.PROMPT, constant("""
                        Drain the current open integration queue across the known current-mission repositories in this act.
                        Include DEV-, QA-, and Architect-authored PRs. Process oldest safe PRs first, but never let an unsafe,
                        conflicted, stale, or ambiguous PR block independent safe PRs behind it.

                        For each candidate, fresh-read the exact owner/repo/PR and linked Plane work needed to verify:
                        intended base, current head/base, mergeability/conflicts, credible validation, acceptance scope,
                        and absence of a current product/QA blocker. If safe, merge it immediately and continue to the next
                        candidate in the same act. If another candidate targets the same repository after a merge, refresh
                        its PR/base state before deciding. QA test-code PRs in the system repository are first-class.

                        Skip unsafe candidates and record concise blocker evidence. If one remaining candidate needs a
                        concrete source-level conflict recovery or small integration patch that does not require product
                        judgment, preserve its exact owner/repo/PR locator for the follow-up recovery decision. Do not
                        require Director approval, QA "green", review-ready phrases, or chat handoffs. Continue until no
                        immediately safe candidate remains or the tool execution budget is reached.
                        """))
                .to("tack:act?id=architect-integration-batch&toolProfile=" + IntegrationToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Did the batch leave a current PR with a concrete source-level conflict or small integration patch
                        that Architect can safely repair without a product/QA judgment? Answer yes only when the batch
                        preserved an exact owner/repo/PR locator and concrete recovery evidence.
                        """))
                .to("tack:decide?id=architect-needs-conflict-recovery")
                .choice()
                    .when(exchangeProperty("decision.architect-needs-conflict-recovery.result").isEqualTo("yes"))
                        .setHeader(TackHeaders.PROMPT, constant("""
                                Recover only the exact preserved PR against its current intended base. Resolve the concrete
                                conflict/integration incompatibility, preserve product behavior, rerun relevant validation,
                                commit/push to the same branch, and reuse the same PR. If resolution requires product
                                judgment, do not guess; stop with the exact question for PO. The next integration batch
                                will fresh-read and merge it when safe.
                                """))
                        .to("tack:act?id=architect-conflict-recovery&toolProfile="
                                + ArchitectSourceControlWriteToolProfile.ID)
                .end();

        from("direct:architect-focus-coordination")
                .routeId("architect-focus-coordination")
                .to("direct:architect-awareness");

        from("direct:architect-awareness")
                .routeId("architect-awareness")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Use the current-mission shared channel for ordinary team coordination. Send/reply once only when
                        repository architecture, integration result, conflict, product question, QA finding, or DEV unblock
                        will help another routine act sooner. Chat is awareness only and never establishes merge authority
                        or an approval gate. Missing channel access is onboarding work, not a reason
                        to use a DM. Avoid generic status.
                        """))
                .to("tack:act?id=architect-awareness&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:architect-focus-complete")
                .routeId("architect-focus-complete")
                .setProperty("decision.architect-mission-complete.result", constant("yes"));

        from("direct:architect-focus-wait")
                .routeId("architect-focus-wait")
                .delay(120000);
    }
}
