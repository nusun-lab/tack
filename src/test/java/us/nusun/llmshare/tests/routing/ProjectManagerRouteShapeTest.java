package us.nusun.llmshare.tests.routing;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ProjectManagerRouteShapeTest {
    @Test
    void projectManagerRoutePrioritizesCollaborationSetupBeforePlanningWork() throws Exception {
        String source = Files.readString(Path.of("src/main/java/us/nusun/llmshare/agent/ProjectManager.java"));

        assertTrue(source.contains("forum-fallback-check"));
        assertTrue(source.contains("chat-channel-setup"));
        assertTrue(source.contains("plane-project-setup"));
        assertTrue(source.contains("gitea-wiki-repo-setup"));
        assertTrue(source.contains("forum-announcement"));
        assertTrue(source.contains("direct:software-team-check-collaboration-setup"));
        assertTrue(source.contains("${exchangeProperty.collaborationSetupComplete} != true"));
        assertTrue(source.contains("bootstrapAttemptCount"));
        assertTrue(source.contains("${exchangeProperty.collaborationSetupComplete} != true && ${exchangeProperty.bootstrapAttemptCount} < {{agents.software-team.max-cycles}}"));
        assertTrue(source.indexOf("bootstrapAttemptCount")
                < source.lastIndexOf("forum-fallback-check"));
    }
}
