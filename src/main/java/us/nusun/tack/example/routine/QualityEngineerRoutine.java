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
                        "direct:qa-focus-test-engineering")
                .subroutine("defect-retest", "Retest repaired product defects and close proven fixes.",
                        "direct:qa-focus-defect-retest")
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
                        Exact Kaneo workspace/project IDs and slug, QA user ID, Mattermost team/channel IDs, system
                        owner/repo and test paths. Keep current Epic acceptance, Test and Defect locators, PRs,
                        behavioral evidence, execution limits and outstanding questions.
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
                            - test-review: an assigned Test's PR changed state or requires source work after review.
                            - test-engineering: an assigned to-do/in-progress Test has actionable authoring or execution work.
                            - test-planning: a non-planned test lacks QA ownership; a dependency-ready planned Test
                              can enter the executable queue; a Test needs QE-owned reset to to-do after PO rejected its
                              linked Defect and clarified intent; an obsolete Test should be archived; or ready/active Epics
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
                    .to("tack:decide?id=qa-focus&choices=defect-retest,defect-feedback,test-review,test-engineering,"
                            + "test-planning,epic-evidence,coordination,complete,wait")
                    .toD("direct:qa-focus-${exchangeProperty.decision.qa-focus.result}")
                .end()
                .end()
                .to("tack:thought-exit?name=qa-root");

        from("direct:qa-orientation")
                .routeId("qa-orientation")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Find to-do and in-progress Epics, your assigned Tests in to-do, in-progress or in-review, and open Defects.
                        Use targeted reads for the Epic's Gherkin, child relations, integrated implementation and system-test
                        evidence. Separate pending test PRs from merged or returned reviews. Identify missing coverage,
                        dependency-ready planned Tests, clarified intent after a rejected Defect and merged fixes awaiting retest.
                        Check targeted chat for fresh QA questions. Cross-component behavior tests belong in the system repo.
                        """))
                .to("tack:act?id=qa-orientation&toolProfile=" + WorkspaceReadonlyToolProfile.ID);

        from("direct:qa-focus-test-planning")
                .routeId("qa-focus-test-planning")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Own Test lifecycle. Resolve your Kaneo user and repair any verified active Test ownership gap.
                        Create or refine missing coverage for product-ready or active Epic Gherkin. Create planned Tests with a task-specific test
                        label and a subtask relation from the Epic. Describe the observable scenario and assign yourself.
                        Promote dependency-ready work to maintain roughly 2–3 active Test units and 2–3 executable to-do
                        Tests where practical. Authoring can run before implementation; gate execution when behavior is unavailable.
                        In-review or deployment-only waits do not consume active execution capacity. Surface ambiguity to PO.
                        When PO rejects a linked Defect and clarifies intent, return its Test to to-do when that retest
                        is actionable. Leave integration-only waits in-review. Archive obsolete Tests with a reason.
                        """))
                .to("tack:act?id=qa-test-planning&toolProfile=" + QualityManagementToolProfile.ID)
                .to("direct:qa-awareness");

        from("direct:qa-focus-test-review")
                .routeId("qa-focus-test-review")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        One Test integration handoff: exact Test and Gitea PR, verified head/base and merge,
                        integrated automation and required behavioral execution still outstanding.
                        """))
                .to("tack:thought-enter?name=qa-test-review-focus&maxTokens=350")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Reconcile one assigned test-labeled Test using its exact Gitea PR. Leave an open required
                        test-code PR in-review while waiting for integration. After verified integration, resume
                        in-progress when behavior still needs execution. Concrete source feedback or unmerged closure
                        also returns it to in-progress for revision. Move to done only when required automation is
                        integrated and actual required behavioral evidence is recorded. Record the exact merge and checks.
                        """))
                .to("tack:act?id=qa-test-review&toolProfile=" + QualityManagementToolProfile.ID)
                .to("direct:qa-awareness")
                .to("tack:thought-exit?name=qa-test-review-focus");

        from("direct:qa-focus-test-engineering")
                .routeId("qa-focus-test-engineering")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        One QA test-engineering unit: exact Kaneo Test classification/owner, Epic Gherkin, system-repo
                        locator, branch/PR, current test evidence, execution limits, and concrete blocker.
                        """))
                .to("tack:thought-enter?name=qa-test-engineering-focus&maxTokens=500")
                .to("direct:qa-test-engineering")
                .to("direct:qa-awareness")
                .to("tack:thought-exit?name=qa-test-engineering-focus");

        from("direct:qa-test-engineering")
                .routeId("qa-test-engineering")
                .setProperty("decision.qa-has-test-work.result", constant("no"))
                .setHeader(TackHeaders.PROMPT, constant("""
                        Find assigned Tests with in-progress or to-do filters. Skip integration-only waits. Verify the test label,
                        Epic parent, Gherkin, system repository and done prerequisites before claiming to-do. For a product-ready
                        Epic you can author tests before implementation is available. A done Epic permits a clarified retest
                        after PO rejects a related Defect and records the expected behavior. Use the dedicated status tool.
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
                                Does this Test currently need an automation change? Choose author when coverage is missing or
                                current test code needs revision, even before implementation. Choose execute when existing
                                integrated automation can exercise the expected behavior.
                                """))
                        .to("tack:decide?id=qa-test-next-step&choices=author,execute")
                        .toD("direct:qa-test-${exchangeProperty.decision.qa-test-next-step.result}")
                        .setHeader(TackHeaders.PROMPT, constant("""
                                Record exact owner/repo/PR and actual evidence on the selected Test. An open required
                                test-code PR awaiting integration moves it to in-review. After integration resume
                                in-progress if execution remains. Done requires integrated automation where applicable
                                and actual required behavioral evidence. Leave Epic lifecycle with PO.
                                """))
                        .to("tack:act?id=qa-test-evidence-update&toolProfile=" + QualityManagementToolProfile.ID)
                .end();

        from("direct:qa-test-author")
                .routeId("qa-test-author")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Author or revise one durable behavior-test slice from the selected Epic's Gherkin in the system
                        repository. Reuse the QA branch and PR. Open the required test-code PR with evidence of checks
                        actually run. If implementation is absent, report what compiled or otherwise ran and what
                        behavioral execution must wait.
                        """))
                .to("tack:act?id=qa-test-engineering-step&toolProfile=" + QualitySourceControlWriteToolProfile.ID);

        from("direct:qa-test-execute")
                .routeId("qa-test-execute")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Execute the selected Test against current integrated source using the system repository test path.
                        Local sandbox execution is evidence for behavior it directly exercises. Record exact integrated
                        revisions, commands, results and any behavior that requires external deployment.
                        """))
                .to("tack:act?id=qa-test-execution-step&toolProfile=" + QualitySourceControlWriteToolProfile.ID);

        from("direct:qa-focus-defect-feedback")
                .routeId("qa-focus-defect-feedback")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Record a reproducible product failure only when no equivalent open Defect exists. Create a planned
                        Defect with defect classification and no-priority. Put expected and actual behavior in its description.
                        Add reproduction and relevant test commit/PR evidence in a comment. Relate the originating Test to
                        the Defect with related. Leave Epic association, product priority and repair assignment unset until
                        PO triage. Record the failure on the originating Test and preserve in-review if required test
                        code still awaits integration. Send product ambiguity to PO and handle broken test code as Test work.
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
                .to("direct:qa-awareness")
                .to("tack:thought-exit?name=qa-defect-retest-focus");

        from("direct:qa-defect-retest")
                .routeId("qa-defect-retest")
                .setProperty("decision.qa-defect-fixed.result", constant("no"))
                .setHeader(TackHeaders.PROMPT, constant("""
                        Select one in-progress Defect whose fix is verified merged and whose retest is actionable. Read the Epic
                        via subtask and the originating Test via related. Check the reproduction, exact fix PR or commit, and
                        integration of any previously opened required QA PR. Wait for changed evidence if that PR is still open.
                        """))
                .to("tack:act?id=qa-defect-retest-context&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        If the selected Defect and merged fix are verified, retest the integrated source locally or in an
                        available target environment using the system-repo behavior test. Add a focused regression test when the defect exposes missing durable
                        coverage. If the retest creates a required QA PR, preserve its exact locator and treat closure as
                        pending until it integrates. Preserve exact
                        execution evidence. Do not create a repository or alter product intent.
                        """))
                .to("tack:act?id=qa-defect-retest-execution&toolProfile=" + QualitySourceControlWriteToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Does current retest evidence demonstrate that the originally failed behavior is now fixed and the
                        relevant acceptance behavior passes? Answer yes only for direct evidence on current integrated source,
                        and only when any regression-test PR required by this retest is already merged. An open/unmerged
                        QA regression-test PR means no even if the local test execution passes.
                        """))
                .to("tack:decide?id=qa-defect-fixed")
                .setHeader(TackHeaders.PROMPT, simple("""
                        Update the selected defect and its originating test with the retest evidence. DEFECT FIXED:
                        ${exchangeProperty.decision.qa-defect-fixed.result}
                        Act only on a selected Defect and verified originating Test.
                        If yes, close the Defect as done and close the Test only when its own integrated automation and
                        required behavior evidence are complete. If no, keep the Defect in-progress and move its Test to
                        in-review when its required QA PR is open. Record pending evidence or the concrete retest failure
                        for PM, Architect and the repair engineer.
                        """))
                .to("tack:act?id=qa-defect-retest-update&toolProfile=" + QualityManagementToolProfile.ID);

        from("direct:qa-focus-epic-evidence")
                .routeId("qa-focus-epic-evidence")
                .to("direct:qa-epic-evidence");

        from("direct:qa-epic-evidence")
                .routeId("qa-epic-evidence")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant(
                        "Exact Epic, Test and related Defect locators, integrated versions and behavioral evidence for PO acceptance."))
                .to("tack:thought-enter?name=qa-epic-evidence-focus&maxTokens=400")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Assess the active Epic against its PO-authored Given/When/Then acceptance using current integrated
                        Story implementation plus test/system behavior evidence. Read only child work/evidence needed.
                        Add a concise evidence comment on the Epic when results change: behaviors proven, tests/executions supporting
                        them, unresolved gaps, and open defect findings. PO owns the acceptance decision and Epic status.
                        """))
                .to("tack:act?id=qa-epic-evidence&toolProfile=" + QualityManagementToolProfile.ID)
                .to("direct:qa-awareness")
                .to("tack:thought-exit?name=qa-epic-evidence-focus");

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
