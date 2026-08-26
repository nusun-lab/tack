package us.nusun.llmshare.framework.tests.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** End-state, forbidden-outcome, and bounded-termination assertions. */
public final class ContractScenarioAssertions {
    private ContractScenarioAssertions() {
    }

    public static void assertSatisfied(ContractExecution execution) {
        ContractScenario scenario = execution.scenario();
        ContractWorld world = execution.world();
        List<String> violations = new ArrayList<>();

        if (execution.routeFailure() != null) {
            violations.add("production route failed: " + execution.routeFailure());
        }

        Map<String, ContractWorld.PullRequestState> actualStates = world.pullRequestStates();
        for (ContractScenario.PullRequestExpectation expectation : list(scenario.then().eventually())) {
            ContractWorld.PullRequestState expected = state(expectation.state());
            ContractWorld.PullRequestState actual = actualStates.get(expectation.pullRequest());
            if (actual != expected) {
                violations.add("expected pull request %s=%s but was %s"
                        .formatted(expectation.pullRequest(), expected, actual));
            }
        }

        for (String forbidden : list(scenario.then().never())) {
            List<ContractWorld.Event> occurrences = world.events().stream()
                    .filter(event -> canonical(event.action()).equals(canonical(forbidden)))
                    .toList();
            if (!occurrences.isEmpty()) {
                violations.add("forbidden outcome occurred: " + forbidden + " (" + occurrences.size() + " time(s))");
            }
        }

        int limit = scenario.then().limits().productiveIterations();
        if (execution.productiveIterations() == null) {
            violations.add("productive iteration counter was absent after route termination");
        } else if (execution.productiveIterations() > limit) {
            violations.add("productive iterations %d exceeded limit %d"
                    .formatted(execution.productiveIterations(), limit));
        }

        if (!violations.isEmpty()) {
            throw new AssertionError(message(execution, violations, actualStates));
        }
    }

    private static String message(
            ContractExecution execution,
            List<String> violations,
            Map<String, ContractWorld.PullRequestState> actualStates) {
        StringBuilder expected = new StringBuilder();
        for (ContractScenario.PullRequestExpectation expectation : list(execution.scenario().then().eventually())) {
            expected.append("  ").append(expectation.pullRequest()).append('=').append(state(expectation.state())).append('\n');
        }
        return """
                Contract scenario '%s' failed:
                %s

                Expected durable outcomes:
                %sActual pull request states:
                  %s

                Productive iterations: %s (limit %d)

                Final world state:
                %s

                Audit history:
                %s
                """.formatted(
                execution.scenario().scenario(),
                violations.stream().map(violation -> "  - " + violation).reduce((a, b) -> a + "\n" + b).orElse(""),
                expected,
                actualStates,
                execution.productiveIterations(),
                execution.scenario().then().limits().productiveIterations(),
                indent(execution.world().describeFinalState()),
                indent(execution.world().describeAudit()));
    }

    private static ContractWorld.PullRequestState state(String value) {
        return ContractWorld.PullRequestState.valueOf(canonical(value).toUpperCase(Locale.ROOT));
    }

    private static String canonical(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replace('-', '_')
                .replace(' ', '_')
                .toLowerCase(Locale.ROOT);
    }

    private static <T> List<T> list(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static String indent(String text) {
        return text.lines().map(line -> "  " + line).reduce((a, b) -> a + "\n" + b).orElse("  (none)");
    }
}
