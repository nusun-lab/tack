package us.nusun.tack.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.tack.example.tool.ArchitectSourceControlWriteToolProfile;
import us.nusun.tack.example.tool.EngineeringWorkToolProfile;
import us.nusun.tack.example.tool.IntegrationToolProfile;
import us.nusun.tack.example.tool.SourceControlToolProfile;
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
                        "direct:architect-focus-integration")
                .subroutine("implementation", "Execute assigned architecture-heavy engineering work.",
                        "direct:architect-focus-implementation")
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
                        Exact Kaneo workspace/project IDs and slug, authenticated user, Mattermost team/channel IDs, and Gitea
                        system/component owner/repo pairs and readiness. Keep architecture decisions, assigned work locators,
                        open PRs, conflicts, integration blockers and cross-role questions.
                        """))
                .to("tack:thought-enter?name=architect-root&maxTokens=450")
                .setProperty("decision.architect-mission-complete.result", constant("no"))
                .loopDoWhile(exchangeProperty("decision.architect-mission-complete.result").isNotEqualTo("yes"))
                    .to("direct:architect-orientation")
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Choose exactly one highest-value Architect focus.
                            - repository-architecture: system repo/component repo model is missing, incomplete, or
                              inconsistent with the current architecture.
                            - reconcile: an ARCH-assigned Story or Defect needs a status update after verified merge or
                              concrete in-review feedback requiring source changes.
                            - integration: any current-mission open PR can be safely merged, needs conflict recovery, or
                              needs a small integration adjustment. Architect owns and drains the whole integration queue,
                              including Architect-authored PRs. Safe integration work outranks new ARCH implementation work.
                            - implementation: an assigned, classification-verified ARCH story or defect is actionable
                              and no safe integration candidate is waiting.
                            - coordination: a fresh architecture/product/QA/developer question needs Architect judgment.
                            - complete: every required product Epic is accepted and done, the repository model is established, no
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
                        agent-workspace repository map already in thought. Filter tasks by your Kaneo user ID in to-do,
                        in-progress and in-review. Check verification gaps in repository architecture, assigned work needing
                        reconciliation, open PRs across known mission repos, and actionable Defects before Stories.
                        Check targeted conversation for a fresh question when needed. Integration candidates outrank new
                        implementation. Report exact locators and evidence without mutation.
                        """))
                .to("tack:act?id=architect-orientation&toolProfile=" + WorkspaceReadonlyToolProfile.ID);

        from("direct:architect-focus-repository-architecture")
                .routeId("architect-focus-repository-architecture")
                .to("direct:architect-repository-architecture");

        from("direct:architect-repository-architecture")
                .routeId("architect-repository-architecture")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant(
                        "Exact system/component repository locators, responsibilities, canonical baseline paths and repository readiness."))
                .to("tack:thought-enter?name=architect-repository-architecture-focus&maxTokens=400")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Make one concrete repository-architecture advance. Use gitea.create_repo and organization agent-workspace
                        when provisioning is required. Reuse current mission repositories under agent-workspace when their
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
                .to("direct:architect-awareness")
                .to("tack:thought-exit?name=architect-repository-architecture-focus");

        from("direct:architect-focus-reconcile")
                .routeId("architect-focus-reconcile")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Reconcile one ARCH-assigned Story or Defect using its exact Gitea PR. Leave an open PR awaiting
                        merge in-review. Concrete feedback requiring implementation or unmerged closure returns the task
                        to in-progress. After verified merge to the intended base, move a Story to done only with required
                        validation. Return a merged Defect to in-progress for QA retest. Record exact merge/validation
                        evidence, and leave successful Defect closure to QA.
                        """))
                .to("tack:act?id=architect-story-reconcile&toolProfile=" + EngineeringWorkToolProfile.ID)
                .to("direct:architect-awareness");

        from("direct:architect-focus-implementation")
                .routeId("architect-focus-implementation")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        One architecture-owned engineering unit: exact Kaneo id/classification/owner, Epic acceptance,
                        repository locator, branch/PR, dependency state, validation, and current blocker.
                        """))
                .to("tack:thought-enter?name=architect-implementation-focus&maxTokens=500")
                .to("direct:architect-implementation")
                .to("direct:architect-awareness")
                .to("tack:thought-exit?name=architect-implementation-focus");

        from("direct:architect-implementation")
                .routeId("architect-implementation")
                .setProperty("decision.architect-has-work.result", constant("no"))
                .setHeader(TackHeaders.PROMPT, constant("""
                        Use your authenticated Kaneo user ID to list assigned in-progress and to-do tasks. Prioritize
                        actionable Defects before Stories, and in-progress before to-do within each kind. Skip in-review,
                        integration-only waits and Defects awaiting QA. Verify exact labels, Epic relation and acceptance,
                        repository and done incoming prerequisites. The first executable assigned Story can start under a
                        to-do Epic. Claim one eligible to-do task with the dedicated status tool.
                        """))
                .to("tack:act?id=architect-work-select&toolProfile=" + EngineeringWorkToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Is one valid ARCH-assigned engineering unit now selected in-progress with enough context to
                        implement? Answer yes or no.
                        """))
                .to("tack:decide?id=architect-has-work")
                .choice()
                    .when(exchangeProperty("decision.architect-has-work.result").isEqualTo("yes"))
                        .setHeader(TackHeaders.PROMPT, constant("""
                                Choose investigation for a selected Story explicitly asking a bounded technical or
                                topology question. Choose implementation for source delivery or defect repair.
                                """))
                        .to("tack:decide?id=architect-work-kind&choices=investigation,implementation")
                        .toD("direct:architect-work-${exchangeProperty.decision.architect-work-kind.result}")
                        .setHeader(TackHeaders.PROMPT, constant("""
                                Record exact owner/repo/PR, head and validation evidence on the selected task. Move it to
                                in-review when an open implementation PR has credible validation. Leave incomplete work
                                in-progress with its concrete next action. Reconcile after merge in the separate focus.
                                For an investigation, record verified findings for PM. A Story whose sole evidence
                                objective is fulfilled can be done without a PR.
                                """))
                        .to("tack:act?id=architect-work-evidence&toolProfile=" + EngineeringWorkToolProfile.ID)
                .end();

        from("direct:architect-work-investigation")
                .routeId("architect-work-investigation")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Inspect the source boundaries, contracts and related tasks relevant to the selected technical
                        question. Return verified findings and dependency implications for PM, including unresolved
                        product questions. Use source and task reads to answer the investigation.
                        """))
                .to("tack:act?id=architect-topology-investigation&toolProfile=" + WorkspaceReadonlyToolProfile.ID);

        from("direct:architect-work-implementation")
                .routeId("architect-work-implementation")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Implement one coherent assigned patch in the established system or component repository. Reuse
                        the task branch/PR and validate directly in the sandbox. Repository provisioning has its own focus.
                        """))
                .to("tack:act?id=architect-implementation-step&toolProfile=" + ArchitectSourceControlWriteToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Check the exact branch/head and Gitea PR state. Verify the evidence of checks actually run,
                        applicable acceptance scope and remaining limitations.
                        """))
                .to("tack:act?id=architect-validation-step&toolProfile=" + SourceControlToolProfile.ID);

        from("direct:architect-focus-integration")
                .routeId("architect-focus-integration")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Drain the current integration queue. Preserve exact selected Gitea owner/repo/PR, linked Kaneo
                        work, head/base, mergeability, validation evidence, and any concrete blocker across selection,
                        conflict recovery, and final merge. Exact locators are the most important memory in this scope.
                        """))
                .to("tack:thought-enter?name=architect-integration-focus&maxTokens=550")
                .to("direct:architect-integration-focus")
                .to("direct:architect-awareness")
                .to("tack:thought-exit?name=architect-integration-focus");

        from("direct:architect-integration-focus")
                .routeId("architect-integration-focus")
                .setProperty("architectIntegrationIterations", constant(0))
                .loopDoWhile(and(exchangeProperty("architectIntegrationIterations").isLessThan(4),
                        or(exchangeProperty("architectIntegrationIterations").isEqualTo(0),
                            or(exchangeProperty("act.architect-integration-batch.durableProgress").isEqualTo(true),
                               exchangeProperty("act.architect-conflict-recovery.durableProgress").isEqualTo(true)))))
                    .setProperty("architectIntegrationIterations",
                            simple("${exchangeProperty.architectIntegrationIterations}++", Integer.class))
                    .to("direct:architect-integration")
                .end();

        from("direct:architect-integration")
                .routeId("architect-integration")
                .setProperty("decision.architect-needs-conflict-recovery.result", constant("no"))
                .setProperty("act.architect-integration-batch.durableProgress", constant(false))
                .setProperty("act.architect-conflict-recovery.durableProgress", constant(false))
                .setHeader(TackHeaders.PROMPT, constant("""
                        Drain the current open integration queue across the known current-mission repositories in this act.
                        Include DEV-, QA-, and Architect-authored PRs. Process oldest safe PRs first, but never let an unsafe,
                        conflicted, stale, or ambiguous PR block independent safe PRs behind it.

                        For each candidate, fresh-read the exact owner/repo/PR and linked Kaneo work needed to verify:
                        intended base, current head/base, mergeability/conflicts, credible validation, acceptance scope,
                        and absence of a current product/QA blocker. If safe, merge it immediately and continue to the next
                        candidate in the same act. If another candidate targets the same repository after a merge, refresh
                        its PR/base state before deciding. QA test-code PRs in the system repository are first-class.

                        Skip unsafe candidates and record concise blocker evidence. If one remaining candidate needs a
                        concrete source-level conflict recovery or small integration patch that does not require product
                        judgment, preserve its exact owner/repo/PR locator for the follow-up recovery decision. Do not
                        require a Kaneo in-review transition, Director approval, QA "green", review-ready phrases,
                        or chat handoffs. Continue until no immediately safe candidate remains or the tool budget is reached.
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
