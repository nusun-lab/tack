package us.nusun.llmshare.framework.tests.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Path-based end-state, unchanged-state, event, and bounded-execution assertions. */
public final class ContractScenarioAssertions {
    private static final Object MISSING = new Object();

    private ContractScenarioAssertions() {
    }

    public static void assertSatisfied(ContractExecution execution) {
        ContractScenario scenario = execution.scenario();
        ContractWorld world = execution.world();
        Map<String, Object> finalState = world.snapshot();
        List<String> violations = new ArrayList<>();

        if (execution.routeFailure() != null
                && !(execution.routeFailure() instanceof ContractLimitExceededException)) {
            violations.add("production route failed: " + execution.routeFailure());
        }

        for (ContractScenario.StateExpectation expectation : list(scenario.then().eventually())) {
            Object actual = valueAt(finalState, expectation.path());
            if (actual == MISSING) {
                violations.add("expected %s=%s but the path does not exist"
                        .formatted(expectation.path(), expectation.equals()));
            } else if (!Objects.deepEquals(expectation.equals(), actual)) {
                violations.add("expected %s=%s but was %s"
                        .formatted(expectation.path(), expectation.equals(), actual));
            }
        }

        for (String path : list(scenario.then().unchanged())) {
            Object initial = valueAt(execution.initialWorldState(), path);
            Object actual = valueAt(finalState, path);
            if (initial == MISSING || actual == MISSING) {
                violations.add("unchanged path does not exist in both snapshots: " + path);
            } else if (!Objects.deepEquals(initial, actual)) {
                violations.add("expected %s to remain unchanged but it changed from %s to %s"
                        .formatted(path, initial, actual));
            }
        }

        for (ContractScenario.EventExpectation forbidden : list(scenario.then().never())) {
            List<ContractWorld.Event> occurrences = world.events().stream()
                    .filter(event -> canonical(event.action()).equals(canonical(forbidden.event())))
                    .toList();
            if (!occurrences.isEmpty()) {
                violations.add("forbidden event occurred: " + forbidden.event()
                        + " (" + occurrences.size() + " time(s))");
            }
        }

        for (ContractScenario.Limit limit : list(scenario.then().limits())) {
            Integer actual = execution.metrics().get(limit.metric());
            if (actual == null) {
                violations.add("execution metric was not captured: " + limit.metric());
            } else if (actual > limit.atMost()) {
                violations.add("metric %s=%d exceeded limit %d"
                        .formatted(limit.metric(), actual, limit.atMost()));
            }
        }

        if (!violations.isEmpty()) {
            throw new AssertionError(message(execution, violations));
        }
    }

    private static String message(ContractExecution execution, List<String> violations) {
        String expected = list(execution.scenario().then().eventually()).stream()
                .map(expectation -> "  " + expectation.path() + '=' + expectation.equals())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("  (none)");
        return """
                Contract scenario '%s' failed:
                %s

                Expected durable outcomes:
                %s

                Unchanged paths: %s
                Execution metrics: %s
                Execution stop: %s

                Final world state:
                %s

                Audit history:
                %s
                """.formatted(
                execution.scenario().scenario(),
                violations.stream().map(violation -> "  - " + violation).reduce((a, b) -> a + "\n" + b).orElse(""),
                expected,
                list(execution.scenario().then().unchanged()),
                execution.metrics(),
                execution.routeFailure() == null ? "completed normally" : execution.routeFailure(),
                indent(execution.world().describeFinalState()),
                indent(execution.world().describeAudit()));
    }

    private static Object valueAt(Map<String, ?> root, String path) {
        Object current = root;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(segment)) {
                return MISSING;
            }
            current = map.get(segment);
        }
        return current;
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
