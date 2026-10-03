package us.nusun.llmshare.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.example.tool.QualityManagementToolProfile;
import us.nusun.llmshare.example.tool.QualitySourceControlWriteToolProfile;
import us.nusun.llmshare.example.tool.TeamChatToolProfile;
import us.nusun.llmshare.example.tool.WorkspaceReadonlyToolProfile;
import us.nusun.llmshare.framework.inventory.RoutineProvider;
import us.nusun.llmshare.framework.model.LlmPassType;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.tack.TackHeaders;

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
                        Durable QA anchors: active type:epic set/acceptance intent, type:task + work:test items,
                        system repository/test paths, execution evidence, type:bug + work:defect items, ambiguities,
                        and blockers.
                        """))
                .to("tack:thought-enter?name=qa-root&maxTokens=450")
                .setProperty("decision.qa-mission-complete.result", constant("no"))
                .loopDoWhile(exchangeProperty("decision.qa-mission-complete.result").isNotEqualTo("yes"))
                    .to("direct:qa-orientation")
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Choose exactly one highest-value QA focus.
                            - defect-retest: an In Progress work:defect Bug has merged-fix evidence and needs QA retest.
                            - defect-feedback: current test evidence demonstrates a new product behavior failure that is not
                              already represented by an open defect.
                            - test-engineering: assigned Todo/In Progress work:test has executable test engineering/evidence work.
                            - test-planning: a non-Backlog work:test Task lacks QA ownership, or active Epics lack
                              sufficient work:test coverage and QA has room to maintain roughly 2-3 active and 2-3 queued
                              executable test units.
                            - epic-evidence: one active Epic's implementation/test work is sufficiently settled to publish
                              a current acceptance-evidence summary for PO.
                            - coordination: a fresh PO/DEV/ARCH/PM question needs QA participation.
                            - complete: every required product Epic is terminal, no QA test engineering/evidence remains,
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
                        Read the smallest current-mission slice needed to rank QA focus. Identify the type:epic items
                        currently In Progress and their PO-authored Given/When/Then acceptance; existing children carrying
                        type:task + work:test; assigned QA
                        lifecycle; relevant implementation progress; system repository and behavior-test paths; newest
                        execution evidence; open work:defect Bugs; merged-fix evidence requiring retest; ambiguity or
                        testability blockers; one fresh QA-related exchange. Retrieve selected children when parent/context
                        is needed. The system repository is the canonical home for cross-component system behavior tests.
                        Report observations only. Do not mutate.
                        """))
                .to("tack:act?id=qa-orientation&toolProfile=" + WorkspaceReadonlyToolProfile.ID);

        from("direct:qa-focus-test-planning")
                .routeId("qa-focus-test-planning")
                .setHeader(TackHeaders.PROMPT, constant("""
                        First repair any classification-verified non-Backlog type:task + work:test item that lacks QA
                        ownership by assigning it to yourself without changing lifecycle. Otherwise create/refine one
                        missing QA test-engineering unit beneath one selected In Progress Epic, derived directly from its
                        PO-authored Given/When/Then acceptance. Ensure type:task and work:test labels exist. Use a child
                        with parent=<Epic UUID> and both labels. Describe the observable scenario/evidence it must prove
                        without prescribing product implementation. If acceptance is ambiguous or incomplete, return the
                        exact product question to PO instead of inventing behavior. Assign it to yourself. Maintain a
                        practical QA flow buffer: roughly 2-3 work:test Tasks In Progress and 2-3 executable Todo tasks
                        queued ahead when enough independent evidence work exists. A Task waiting only on deployment,
                        integration, or another role does not consume active QA execution capacity. These are flow targets,
                        not hard ceilings. Keep truly dependency-blocked foreseeable coverage Backlog. Avoid duplicate
                        coverage and oversized test tasks.
                        """))
                .to("tack:act?id=qa-test-planning&toolProfile=" + QualityManagementToolProfile.ID)
                .to("direct:qa-awareness");

        from("direct:qa-focus-test-engineering")
                .routeId("qa-focus-test-engineering")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        One QA test-engineering unit: exact Plane Task classification/owner, Epic Gherkin, system-repo
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
                        Select exactly one work:test Task assigned to you under an In Progress Epic. Before lifecycle mutation,
                        verify current attached labels include type:task + work:test and that QA remains the assignee.
                        Unknown classification means do not mutate. Prefer actionable In Progress; otherwise claim assigned
                        Todo -> In Progress. Verify exact Task classification, Epic parent/acceptance,
                        system repository, test target, and dependencies. If no valid actionable Task exists, make no mutation.
                        """))
                .to("tack:act?id=qa-test-work-select&toolProfile=" + QualityManagementToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Is one valid actionable work:test Task now selected In Progress with enough Epic/test context to
                        engineer or execute evidence? Answer yes or no.
                        """))
                .to("tack:decide?id=qa-has-test-work")
                .choice()
                    .when(exchangeProperty("decision.qa-has-test-work.result").isEqualTo("yes"))
                        .setHeader(TackHeaders.PROMPT, constant("""
                                Advance exactly one coherent QA engineering slice in the existing system repository.
                                Add/improve durable automated system behavior tests when coverage is missing, or execute
                                existing tests when implementation is ready. Use the selected Task's Epic
                                Given/When/Then acceptance as the behavioral target, not implementation details. Reuse the
                                same QA branch/PR when
                                continuing work. Validate what can actually run and preserve exact pass/fail/limitation
                                evidence. Do not create repositories.
                                """))
                        .to("tack:act?id=qa-test-engineering-step&toolProfile=" + QualitySourceControlWriteToolProfile.ID)
                        .setHeader(TackHeaders.PROMPT, constant("""
                                Update only the selected work:test Task with current branch/PR when applicable, tests changed,
                                execution performed, behavior proven, failures, limitations, and blockers. Keep the Task
                                In Progress while a test-code PR is open or unmerged. Move the Task to Done only when any
                                required test-code PR is verified merged and the durable test engineering plus required
                                execution evidence are complete. Do not move the parent Epic.
                                """))
                        .to("tack:act?id=qa-test-evidence-update&toolProfile=" + QualityManagementToolProfile.ID)
                .end();

        from("direct:qa-focus-defect-feedback")
                .routeId("qa-focus-defect-feedback")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Classify one current failed behavior using evidence. If it is a reproducible product behavior
                        failure and no equivalent open defect exists, ensure type:bug and work:defect labels exist, and
                        create one unparented Backlog work item carrying both labels. Include expected behavior,
                        actual behavior, reproduction/test evidence, relevant test Task/commit/PR, observed scope, and
                        the candidate Epic context as evidence only. Leave parent unset, product priority as none, and
                        repair unassigned so PO owns Epic classification/priority before PM routing.
                        Product ambiguity returns to PO instead of becoming a Bug. Broken tests stay QA-owned test work.
                        Architecture/testability findings are reported to ARCH/PM without falsely creating product defects.
                        """))
                .to("tack:act?id=qa-defect-record&toolProfile=" + QualityManagementToolProfile.ID)
                .to("direct:qa-awareness");

        from("direct:qa-focus-defect-retest")
                .routeId("qa-focus-defect-retest")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        One defect retest: exact Bug classification/owner, Epic acceptance, original failure evidence,
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
                        Select one In Progress work:defect Bug with credible merged-fix evidence. Read its Epic acceptance,
                        original reproduction evidence, fix PR/commit, and relevant system behavior test. Do not retest an
                        unmerged or ambiguous fix.
                        """))
                .to("tack:act?id=qa-defect-retest-context&toolProfile=" + QualityManagementToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Reproduce/retest the selected defect against the current integrated system using the system-repo
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
                        Update only the selected work:defect Bug with the retest evidence. DEFECT FIXED:
                        ${exchangeProperty.decision.qa-defect-fixed.result}
                        If yes, move the Bug to Done and cite the passing integrated regression/retest evidence.
                        If no, keep it In Progress and record the current failure/limitation or pending QA regression-test
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
                        implementation plus work:test/system behavior evidence. Read only child work/evidence needed.
                        Add/update one concise evidence comment on the Epic: behaviors proven, tests/executions supporting
                        them, unresolved gaps, and open defects. Do not redefine acceptance and never move the Epic Done.
                        PO owns acceptance.
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
