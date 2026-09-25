package us.nusun.llmshare.framework.tests.unit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class RoutineLineLengthTest {
    private static final int MAX_LINE_LENGTH = 120;

    private static final List<Path> ROUTINES = List.of(
            Path.of("src/main/java/us/nusun/llmshare/example/routine/ApplicationDeveloperRoutine.java"),
            Path.of("src/main/java/us/nusun/llmshare/example/routine/ProductOwnerRoutine.java"),
            Path.of("src/main/java/us/nusun/llmshare/example/routine/ProjectManagerRoutine.java"),
            Path.of("src/main/java/us/nusun/llmshare/example/routine/DirectorRoutine.java"));

    @Test
    void softwareTeamRoutinesStayReadableWithoutHorizontalScrolling() throws IOException {
        for (Path routine : ROUTINES) {
            List<String> lines = Files.readAllLines(routine);
            for (int i = 0; i < lines.size(); i++) {
                int length = lines.get(i).length();
                assertTrue(
                        length <= MAX_LINE_LENGTH,
                        () -> routine + ":" + (i + 1) + " is " + length
                                + " characters; limit is " + MAX_LINE_LENGTH);
            }
        }
    }
}
