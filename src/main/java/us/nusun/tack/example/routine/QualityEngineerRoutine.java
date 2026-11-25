package us.nusun.tack.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.tack.example.tool.QualityManagementToolProfile;
import us.nusun.tack.example.tool.QualitySourceControlWriteToolProfile;
import us.nusun.tack.example.tool.TeamChatToolProfile;
import us.nusun.tack.example.tool.WorkspaceReadonlyToolProfile;
import us.nusun.tack.framework.routine.RoutineProvider;
import us.nusun.tack.framework.inference.LlmPassType;
import us.nusun.tack.framework.routine.RoutineDefinition;
import us.nusun.tack.framework.routine.TackHeaders;

import java.util.List;

@ApplicationScoped
public class QualityEngineerRoutine extends RouteBuilder implements RoutineProvider {
    public static final String ID = "software-team/quality-engineer";

    @Override
    public List<RoutineDefinition> routines() {
        return List.of(routine());
    }

    public RoutineDefinition routine() {
        return RoutineDefinition.builder(ID)
                .name("Quality Engineer")
                .main("Engineer system-level behavioral evidence for Epic completion.",
                        "direct:example-software-team-quality-engineer")
                .subroutine("test-engineering", "Own and execute system behavior test work.",
                        "direct:qa-test-engineering")
                .subroutine("defect-retest", "Retest repaired product defects and close proven fixes.",
                        "direct:qa-defect-retest")
                .subroutine("epic-evidence", "Publish Epic completion evidence for Product Owner.",
                        "direct:qa-epic-evidence")
                .defaultLane("high-pass")
                .withLaneBinding(LlmPassType.SYNTHESIS, "low-pass")
                .withLaneBinding(LlmPassType.CONSOLIDATION, "low-pass")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-software-team-quality-engineer")
                .routeId("example-software-team-quality-engineer")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Durable QA anchors: active epic set/acceptance intent, test items,
                        system repository/test paths, execution evidence, defect findings, ambiguities, and blockers.
                        """))
                .to("tack:thought-enter?name=qa-root&maxTokens=450")
                .setProperty("decision.qa-mission-complete.result", constant("no"))
                .loopDoWhile(exchangeProperty("decision.qa-mission-complete.result").isNotEqualTo("yes"))
                    .to("direct:qa-orientation")
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Choose exactly one highest-value QA focus.
                            - defect-retest: an in-progress defect has merged-fix evidence and needs QA retest.
                            - defect-feedback: a test has reproducible product-failure evidence not already represented
                              by an open defect.
                            - test-engineering: assigned to-do/in-progress test has executable test engineering/evidence work.
                            - test-planning: a non-planned test lacks QA ownership; a dependency-ready planned Test
                              can enter the executable queue; a Test needs QE-owned reset to to-do after PO rejected its
                              linked Defect and clarified intent; an obsolete Test should be archived; or active Epics
                              lack sufficient test coverage and QA has room for roughly 2-3 active and 2-3 queued
                              executable test units.
                            - epic-evidence: one active Epic's implementation/test work is sufficiently settled to publish
                              a current acceptance-evidence summary for PO.
                            - coordination: a fresh PO/DEV/ARCH/PM question needs QA participation.
                            - complete: every required product Epic is accepted and done, no QA test engineering/evidence remains,
                              no repaired defect awaits retest, and no unresolved QA finding remains.
                            - wait: the mission is not complete and none of the other focuses is useful now.
                            Prefer durable automated evidence over manual review. Choose exactly one value.
                            """))
                    .to("tack:decide?id=qa-focus&choices=defect-retest,defect-feedback,test-engineering,test-planning,epic-evidence,coordination,complete,wait")
                    .toD("direct:qa-focus-${exchangeProperty.decision.qa-focus.result}")
                .end()
                .end()
                .to("tack:thought-exit?name=qa-root");

        from("direct:qa-orientation")
                .routeId("qa-orientation")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Find in-progress Epics, executable Tests assigned to your authenticated user, and relevant open Defects.
                        Use targeted reads for the Epic's Gherkin, child relations, integrated implementation and system-test
                        evidence. Identify missing coverage, dependency-ready planned Tests, clarified Test intent after a
                        rejected Defect, and merged fixes awaiting retest. Cross-component behavior tests belong in the system repo.
                        """))
                .to("tack:act?id=qa-orientation&toolProfile=" + WorkspaceReadonlyToolProfile.ID);

        from("direct:qa-focus-test-planning")
                .routeId("qa-focus-test-planning")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Own Test lifecycle. Resolve your Kaneo user and repair any verified active Test ownership gap.
                        Create or refine missing coverage for active Epic Gherkin. Create planned Tests with a task-specific test
                        label and a subtask relation from the Epic. Describe the observable scenario and assign yourself.
                        Promote dependency-ready work to maintain roughly 2–3 executable to-do Tests. A Test waiting only on
                        integration or deployment does not consume active execution capacity. Surface ambiguity to PO.
                        When PO rejects a linked Defect and clarifies intent, return its Test to to-do for retest. Archive
                        obsolete or duplicate Tests with a recorded reason.
                        """))
                .to("tack:act?id=qa-test-planning&toolProfile=" + QualityManagementToolProfile.ID)
                .to("direct:qa-awareness");

        from("direct:qa-focus-test-engineering")
                .routeId("qa-focus-test-engineering")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        One QA test-engineering unit: exact Kaneo Test classification/owner, Epic Gherkin, system-repo
                        locator, branch/PR, current test evidence, execution limits, and concrete blocker.
                        """))
                .to("tack:thought-enter?name=qa-test-engineering-focus&maxTokens=500")
                .to("direct:qa-test-engineering")
                .to("tack:thought-exit?name=qa-test-engineering-focus")
                .to("direct:qa-awareness");

        from("direct:qa-test-engineering")
                .routeId("qa-test-engineering")
                .setProperty("decision.qa-has-test-work.result", constant("no"))
                .setHeader(TackHeaders.PROMPT, constant("""
                        Find Tests using your authenticated user ID and in-progress or to-do filters. Prefer actionable active
                        work. Verify the test label, Epic parent, Gherkin, system repo, and prerequisites before claiming to-do.
                        Usually the Epic must be in-progress. A done Epic permits a clarified retest after PO rejected a related
                        Defect and recorded the expected behavior. Use the dedicated status tool and select one Test.
                        """))
                .to("tack:act?id=qa-test-work-select&toolProfile=" + QualityManagementToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Is one valid actionable test now selected in-progress with enough Epic/test context to
                        engineer or execute evidence? Answer yes or no.
                        """))
                .to("tack:decide?id=qa-has-test-work")
                .choice()
                    .when(exchangeProperty("decision.qa-has-test-work.result").isEqualTo("yes"))
                        .setHeader(TackHeaders.PROMPT, constant("""
                                Advance exactly one coherent QA engineering slice in the existing system repository.
                                Add/improve durable automated system behavior tests when coverage is missing, or execute
                                existing tests when implementation is ready. Use the selected Test's Epic
                                Given/When/Then acceptance as the behavioral target, not implementation details. Reuse the
                                same QA branch/PR when
                                continuing work. Validate what can actually run and preserve exact pass/fail/limitation
                                evidence. Do not create repositories.
                                """))
                        .to("tack:act?id=qa-test-engineering-step&toolProfile=" + QualitySourceControlWriteToolProfile.ID)
                        .setHeader(TackHeaders.PROMPT, constant("""
                                Update only the selected test with current branch/PR when applicable, tests changed,
                                execution performed, behavior proven, failures, limitations, and blockers. Keep the Test
                                in-progress while a test-code PR is open or unmerged. Move the Test to done only when any
                                required test-code PR is verified merged and the durable test engineering plus required
                                execution evidence are complete. Do not move the parent Epic.
                                """))
                        .to("tack:act?id=qa-test-evidence-update&toolProfile=" + QualityManagementToolProfile.ID)
                .end();

        from("direct:qa-focus-defect-feedback")
                .routeId("qa-focus-defect-feedback")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Record a reproducible product failure only when no equivalent open Defect exists. Create a planned
                        Defect with defect classification and no-priority. Put expected and actual behavior in its description.
                        Add reproduction and relevant test commit/PR evidence in a comment. Relate the originating Test to
                        the Defect with related. Leave Epic association, product priority and repair assignment unset until
                        PO triage. Keep the Test in-progress with its failure evidence. Product ambiguity goes to PO and
                        test failures caused by broken test code stay Test work.
                        """))
                .to("tack:act?id=qa-defect-record&toolProfile=" + QualityManagementToolProfile.ID)
                .to("direct:qa-awareness");

        from("direct:qa-focus-defect-retest")
                .routeId("qa-focus-defect-retest")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        One defect retest: exact defect, Epic acceptance, originating test, original failure evidence,
                        merged fix PR/commit, regression test evidence, and current retest result.
                        """))
                .to("tack:thought-enter?name=qa-defect-retest-focus&maxTokens=500")
                .to("direct:qa-defect-retest")
                .to("tack:thought-exit?name=qa-defect-retest-focus")
                .to("direct:qa-awareness");

        from("direct:qa-defect-retest")
                .routeId("qa-defect-retest")
                .setProperty("decision.qa-defect-fixed.result", constant("no"))
                .setHeader(TackHeaders.PROMPT, constant("""
                        Select one in-progress Defect whose fix is verified merged. Read the Epic via subtask and the
                        originating Test via related, then inspect the reproduction evidence and the exact fix PR or commit.
                        """))
                .to("tack:act?id=qa-defect-retest-context&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        If the selected Defect and merged fix are verified, retest against the integrated system using the system-repo
                        behavior test when available. Add a focused regression test when the defect exposes missing durable
                        coverage. When that test change creates or updates a QA PR, preserve the PR as required evidence
                        and do not treat the defect as closable until the regression-test PR is integrated. Preserve exact
                        execution evidence. Do not create a repository or alter product intent.
                        """))
                .to("tack:act?id=qa-defect-retest-execution&toolProfile=" + QualitySourceControlWriteToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Does current retest evidence demonstrate that the originally failed behavior is now fixed and the
                        relevant acceptance behavior passes? Answer yes only for direct current integrated-system evidence,
                        and only when any regression-test PR required by this retest is already merged. An open/unmerged
                        QA regression-test PR means no even if the local test execution passes.
                        """))
                .to("tack:decide?id=qa-defect-fixed")
                .setHeader(TackHeaders.PROMPT, simple("""
                        Update the selected defect and its originating test with the retest evidence. DEFECT FIXED:
                        ${exchangeProperty.decision.qa-defect-fixed.result}
                        Act only on a selected Defect and verified originating Test.
                        If yes, move the Defect to done and move the originating Test to done when its required evidence
                        is complete, citing the passing integrated regression/retest evidence. If no, keep both in-progress
                        and record the current failure/limitation or pending QA regression-test
                        PR for PM, Architect, and the assigned engineer.
                        """))
                .to("tack:act?id=qa-defect-retest-update&toolProfile=" + QualityManagementToolProfile.ID);

        from("direct:qa-focus-epic-evidence")
                .routeId("qa-focus-epic-evidence")
                .to("direct:qa-epic-evidence");

        from("direct:qa-epic-evidence")
                .routeId("qa-epic-evidence")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Assess the active Epic against its PO-authored Given/When/Then acceptance using current integrated
                        Story implementation plus test/system behavior evidence. Read only child work/evidence needed.
                        Add a concise evidence comment on the Epic when results change: behaviors proven, tests/executions supporting
                        them, unresolved gaps, and open defect findings. PO owns the acceptance decision and Epic status.
                        """))
                .to("tack:act?id=qa-epic-evidence&toolProfile=" + QualityManagementToolProfile.ID)
                .to("direct:qa-awareness");

        from("direct:qa-focus-coordination")
                .routeId("qa-focus-coordination")
                .to("direct:qa-awareness");

        from("direct:qa-awareness")
                .routeId("qa-awareness")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Use the current-mission shared channel for ordinary team coordination. Send/reply once only when QA
                        evidence, new defect, retest result, acceptance ambiguity, testability issue, or validation
                        limitation will help PO, PM, Architect, or Developer act sooner. QA reports evidence; it does not
                        create merge-approval rituals or require a "green" chat phrase. Missing channel access is onboarding
                        work, not a reason to use a DM. Avoid generic status.
                        """))
                .to("tack:act?id=qa-awareness&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:qa-focus-complete")
                .routeId("qa-focus-complete")
                .setProperty("decision.qa-mission-complete.result", constant("yes"));

        from("direct:qa-focus-wait")
                .routeId("qa-focus-wait")
                .delay(120000);
    }
}
