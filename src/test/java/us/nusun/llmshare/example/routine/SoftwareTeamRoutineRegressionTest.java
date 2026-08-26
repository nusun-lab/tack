package us.nusun.llmshare.example.routine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SoftwareTeamRoutineRegressionTest {
    @Test
    void directorRefreshesFoundationBeforeStaffingAndGatesMergeOnCandidate() throws IOException {
        String source = source("DirectorRoutine.java");

        assertOrdered(source,
                "tack:act?id=director-staffing-read",
                "tack:decide?id=director-foundation-ready",
                "tack:create?id=planned-agent-runs");
        assertTrue(source.contains("An empty source repository is valid."));
        assertTrue(source.contains("Work-item or code absence"));

        assertOrdered(source,
                "tack:act?id=director-workspace-review",
                "tack:decide?id=director-has-integration-candidate",
                "decision.director-has-integration-candidate.result").isEqualTo(true))",
                "tack:act?id=director-integration-merge");
    }

    @Test
    void productOwnerDoesNotMutateWithoutSelectedProductWork() throws IOException {
        String source = source("ProductOwnerRoutine.java");

        assertOrdered(source,
                "tack:act?id=po-backlog-review",
                "tack:decide?id=product-owner-has-work",
                "decision.product-owner-has-work.result").isEqualTo(true))",
                "tack:act?id=po-product-context-read");
        assertOrdered(source,
                ".to(\"direct:po-select-product-work\")",
                "decision.product-owner-has-work.result").isEqualTo(true))",
                ".to(\"direct:po-acceptance-work\")",
                ".to(\"direct:po-plane-state-handoff\")");
    }

    private static String source(String file) throws IOException {
        return Files.readString(Path.of("src/main/java/us/nusun/llmshare/example/routine", file));
    }

    private static void assertOrdered(String source, String... markers) {
        int previous = -1;
        for (String marker : markers) {
            int current = source.indexOf(marker, previous + 1);
            assertTrue(current > previous, () -> "Expected marker after previous marker: " + marker);
            previous = current;
        }
    }
}
