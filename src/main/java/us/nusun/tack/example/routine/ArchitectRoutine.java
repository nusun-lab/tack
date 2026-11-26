package us.nusun.tack.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.tack.example.tool.ArchitectSourceControlWriteToolProfile;
import us.nusun.tack.example.tool.EngineeringWorkToolProfile;
import us.nusun.tack.example.tool.IntegrationToolProfile;
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
                        Mission anchor, verified Kaneo workspace ID, project ID/slug and ARCH user ID, and Mattermost team/channel IDs.
                        Keep the documented system/component owner/repo pairs, baseline readiness, relevant task/PR locators and blockers.
                        """))
                .to("tack:thought-enter?name=architect-root&maxTokens=450")
                .to("direct:architect-repository-architecture")
                .setProperty("decision.architect-mission-complete.result", constant("no"))
                .loopDoWhile(exchangeProperty("decision.architect-mission-complete.result").isNotEqualTo("yes"))
                    .to("direct:architect-orientation")
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Choose exactly one highest-value Architect focus.
                            - repository-architecture: evidence identifies missing or incomplete repository setup you can
                              advance. A verified existing baseline satisfies readiness even on an unchanged read.
                            - reconcile: an ARCH-assigned story remains in-progress after its linked
                              PR is verified merged; Architect owns its transition to done.
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
                        If repository readiness is unknown, use a mission-anchor search and verify the system map and one
                        component baseline. Once ready, look for open mission PRs and your assigned to-do or in-progress work.
                        Treat an unchanged verified baseline as ready.
                        """))
                .to("tack:act?id=architect-orientation&toolProfile=" + WorkspaceReadonlyToolProfile.ID);

        from("direct:architect-focus-repository-architecture")
                .routeId("architect-focus-repository-architecture")
                .to("direct:architect-repository-architecture");

        from("direct:architect-repository-architecture")
                .routeId("architect-repository-architecture")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Establish or repair the repository architecture independently of Epic decomposition and staffing. Search
                        the mission anchor in agent-workspace before creating anything. Reuse exact matches and repair incomplete main
                        baselines. Create only verified missing repositories with gitea.create_repo and organization agent-workspace.
                        Use the sandbox for Git, source edits and builds.
                        Publish the smallest locally buildable skeleton on main. Use a placeholder entry point and a build
                        smoke check. Leave Epic behavior and its tests for Stories and QA. In the system repo, document exact
                        component owner/repo pairs and local build/test commands in docs/architecture.md.
                        """))
                .to("tack:act?id=architect-repository-step&toolProfile="
                        + ArchitectSourceControlWriteToolProfile.ID);

        from("direct:architect-focus-reconcile")
                .routeId("architect-focus-reconcile")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Reconcile exactly one ARCH-assigned story whose linked PR is verified merged.
                        Confirm the Story is still in-progress, the PR/commit actually merged to the intended base, and no
                        newer evidence requires source changes. Move that Story to done with concise merge/validation
                        evidence. Never close defect here; repaired Defects remain in-progress for QA retest/closure.
                        """))
                .to("tack:act?id=architect-story-reconcile&toolProfile=" + EngineeringWorkToolProfile.ID)
                .to("direct:architect-awareness");

        from("direct:architect-focus-implementation")
                .routeId("architect-focus-implementation")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Exact project, task, Epic and assignee IDs, repo owner/name and branch/PR. Keep acceptance,
                        dependencies, validation and the next actionable blocker.
                        """))
                .to("tack:thought-enter?name=architect-implementation-focus&maxTokens=500")
                .to("direct:architect-implementation")
                .to("tack:thought-exit?name=architect-implementation-focus")
                .to("direct:architect-awareness");

        from("direct:architect-implementation")
                .routeId("architect-implementation")
                .setProperty("decision.architect-has-work.result", constant("no"))
                .setHeader(TackHeaders.PROMPT, constant("""
                        Use your authenticated Kaneo user ID to list assigned in-progress and to-do tasks. Verify the story
                        or defect label. Prefer actionable in-progress work. A PR merely waiting for integration belongs in
                        the integration queue. Verify the selected task's Epic relation, acceptance intent, repository and
                        incoming prerequisites. The parent Epic may be to-do or in-progress. Claim one eligible to-do task using
                        the dedicated status tool.
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
                                Implement exactly one coherent architecture-owned patch in the existing system/component
                                repository: integration/system wiring, GitOps, repository structure, cross-component
                                contract, architecture-heavy component work, or assigned defect repair. Reuse the task
                                branch/PR when continuing. Validate directly in the sandbox. Do not create a new repository
                                to solve an ordinary implementation task, and do not absorb unrelated DEV work.
                                """))
                        .to("tack:act?id=architect-implementation-step&toolProfile="
                                + ArchitectSourceControlWriteToolProfile.ID)
                        .setHeader(TackHeaders.PROMPT, constant("""
                                Comment on the selected task with branch/commit/PR, changed scope, validation, and
                                concrete blockers. Keep story in-progress while its PR is open; Architect
                                reconciles its assigned Story to done after verified merge. Keep defect in-progress
                                through merge; QA retest closes the Defect.
                                """))
                        .to("tack:act?id=architect-work-evidence&toolProfile=" + EngineeringWorkToolProfile.ID)
                .end();

        from("direct:architect-focus-integration")
                .routeId("architect-focus-integration")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Exact Gitea owner/repo/PR, head/base, linked Kaneo project/ticket, validation and blockers for each
                        selected candidate across merge and conflict recovery.
                        """))
                .to("tack:thought-enter?name=architect-integration-focus&maxTokens=550")
                .to("direct:architect-integration-focus")
                .to("tack:thought-exit?name=architect-integration-focus")
                .to("direct:architect-awareness");

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
                        and absence of a current product/QA blocker. Local build and focused tests can justify integration.
                        Pending deployed-environment evidence is separate from PR readiness. If safe, merge it immediately and continue to the next
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
