package us.nusun.llmshare.framework.tests.unit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class RoutineReadabilityTest {
    private static final int MAX_CODE_LINE = 180;
    private static final int MAX_PROMPT_LINE = 140;

    private static final List<Path> ROUTINES = List.of(
            Path.of("src/main/java/us/nusun/llmshare/example/routine/ApplicationDeveloperRoutine.java"),
            Path.of("src/main/java/us/nusun/llmshare/example/routine/ProductOwnerRoutine.java"),
            Path.of("src/main/java/us/nusun/llmshare/example/routine/ProjectManagerRoutine.java"),
            Path.of("src/main/java/us/nusun/llmshare/example/routine/DirectorRoutine.java"));

    @Test
    void softwareTeamRoutinesStayReadable() throws IOException {
        for (Path routine : ROUTINES) {
            List<String> lines = Files.readAllLines(routine);
            boolean inTextBlock = false;

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                int lineNumber = i + 1;

                assertTrue(
                        line.length() <= MAX_CODE_LINE,
                        () -> routine + ":" + lineNumber + " is " + line.length()
                                + " characters; code limit is " + MAX_CODE_LINE);

                if (inTextBlock || line.contains("TackHeaders.PROMPT")
                        || line.contains("TackHeaders.THOUGHT_PURPOSE")) {
                    assertTrue(
                            line.length() <= MAX_PROMPT_LINE,
                            () -> routine + ":" + lineNumber + " is " + line.length()
                                    + " characters; prompt limit is " + MAX_PROMPT_LINE);
                }

                if (countOccurrences(line, "\"\"\"") % 2 == 1) {
                    inTextBlock = !inTextBlock;
                }
            }
        }
    }

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
