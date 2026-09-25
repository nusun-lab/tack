package us.nusun.llmshare.framework.tests.unit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ContentRoutingShapeTest {
    private static final Map<String, Path> ROUTINES = new LinkedHashMap<>();

    static {
        ROUTINES.put("dev", Path.of("src/main/java/us/nusun/llmshare/example/routine/ApplicationDeveloperRoutine.java"));
        ROUTINES.put("po", Path.of("src/main/java/us/nusun/llmshare/example/routine/ProductOwnerRoutine.java"));
        ROUTINES.put("pm", Path.of("src/main/java/us/nusun/llmshare/example/routine/ProjectManagerRoutine.java"));
        ROUTINES.put("director", Path.of("src/main/java/us/nusun/llmshare/example/routine/DirectorRoutine.java"));
    }

    @Test
    void everyDeclaredFocusHasAMatchingRoute() throws IOException {
        for (Map.Entry<String, Path> entry : ROUTINES.entrySet()) {
            String role = entry.getKey();
            String source = Files.readString(entry.getValue());
            String marker = "tack:decide?id=" + role + "-focus&choices=";
            int start = source.indexOf(marker);
            assertTrue(start >= 0, () -> "Missing focus decision for " + role);

            int end = source.indexOf('"', start);
            assertTrue(end > start, () -> "Malformed focus decision for " + role);

            String choices = source.substring(start + marker.length(), end);
            for (String choice : choices.split(",")) {
                String route = "direct:" + role + "-focus-" + choice;
                assertTrue(
                        source.contains(route),
                        () -> "Missing focus route " + route);
            }

            String dynamicRoute = "direct:" + role
                    + "-focus-${exchangeProperty.decision." + role + "-focus.result}";
            assertTrue(
                    source.contains(dynamicRoute),
                    () -> "Missing dynamic focus dispatch for " + role);
        }
    }
}
