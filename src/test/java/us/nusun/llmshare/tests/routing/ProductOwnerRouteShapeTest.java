package us.nusun.llmshare.tests.routing;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ProductOwnerRouteShapeTest {
    @Test
    void productOwnerRouteStillReadsLikeASimplePlaybook() throws Exception {
        String source = Files.readString(Path.of("src/main/java/us/nusun/llmshare/agent/ProductOwner.java"));

        assertTrue(source.contains(".to(\"direct:log-software-team-step\")"));
        assertTrue(source.contains(".to(\"direct:software-team-routine-step\")"));
        assertFalse(source.contains("routine-step-format"));
        assertFalse(source.contains("routine-formatter"));
        assertFalse(source.contains(".to(\"direct:ask-llm\")"));
    }
}
