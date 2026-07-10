package us.nusun.llmshare.framework.tests.unit;

import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.inventory.ToolProfileRegistry;
import us.nusun.llmshare.framework.model.McpServerDefinition;
import us.nusun.llmshare.framework.model.ToolProfile;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptSafeCatalogTest {
    @Test
    void assignablePersonaPromptJsonExcludesIdentityTokens() {
        String json = FrameworkFixtures.personaCatalog().agentRunStartAssignablePromptJson();

        assertTrue(json.contains("persona-a"));
        assertTrue(json.contains("Persona A"));
        assertTrue(json.contains("@persona-a"));
        assertFalse(json.contains("mattermost-token"));
        assertFalse(json.contains("gitea-token"));
        assertFalse(json.contains("plane-token"));
        assertFalse(json.contains("token"));
    }

    @Test
    void personaInventoryPromptJsonIncludesHandlesButNoTokensOrRosterSelfMarker() {
        String json = FrameworkFixtures.personaCatalog().personaInventoryPromptJson();

        assertTrue(json.contains("persona-a"));
        assertTrue(json.contains("Persona A"));
        assertTrue(json.contains("PA"));
        assertTrue(json.contains("Synthetic framework persona A"));
        assertTrue(json.contains("@persona-a"));
        assertFalse(json.contains("\"self\" : true"));
        assertFalse(json.contains("mattermost-token"));
        assertFalse(json.contains("gitea-token"));
        assertFalse(json.contains("plane-token"));
        assertFalse(json.contains("token"));
    }

    @Test
    void toolProfilePromptJsonExcludesConnectionAndAuthDetails() {
        ToolProfileRegistry registry = new ToolProfileRegistry(List.of(ToolProfile.builder("guided")
                .mcpServer(McpServerDefinition.builder("source-control")
                        .url("https://mcp.example/secret")
                        .authHeaderName("X-Secret-Header")
                        .authHeaderValueFormat("Bearer secret-%s")
                        .extraHeader("x-workspace-slug", "private-workspace")
                        .build())
                .promptGuidance("Read before writing.")
                .build()));

        String json = registry.promptJson();

        assertTrue(json.contains("guided"));
        assertTrue(json.contains("Read before writing."));
        assertTrue(json.contains("source-control"));
        assertFalse(json.contains("https://mcp.example/secret"));
        assertFalse(json.contains("X-Secret-Header"));
        assertFalse(json.contains("Bearer secret"));
        assertFalse(json.contains("x-workspace-slug"));
        assertFalse(json.contains("private-workspace"));
        assertFalse(json.contains("authHeader"));
        assertFalse(json.contains("extraHeaders"));
        assertFalse(json.contains("url"));
    }

    @Test
    void directorRoutineUsesPromptSafeCatalogRenderers() throws Exception {
        String source = Files.readString(Path.of("src/main/java/us/nusun/llmshare/example/routine/DirectorRoutine.java"));

        assertTrue(source.contains("personaCatalog.agentRunStartAssignablePromptJson"));
        assertTrue(source.contains("toolProfileCatalog.promptJson"));
        assertFalse(source.contains("personaCatalog.agentRunStartAssignableJson"));
        assertFalse(source.contains("toolProfileCatalog.allJson"));
    }

    @Test
    void directorRoutineHasToolSpecificReviewsWithoutHardCodedRoleHints() throws Exception {
        String source = Files.readString(Path.of("src/main/java/us/nusun/llmshare/example/routine/DirectorRoutine.java"));

        assertTrue(source.contains("toolProfile(\"source-control\")"));
        assertTrue(source.contains("toolProfile(\"project-management\")"));
        assertTrue(source.contains("toolProfile(\"team-chat\")"));
        assertTrue(source.contains("Look for verified mission anchors"));
        assertTrue(source.contains("Look for work item states"));
        assertTrue(source.contains("Look for direct requests"));
        assertTrue(source.contains("participate when useful"));
        assertTrue(source.contains("reply in the existing"));
        assertTrue(source.contains("top-level mission 100% complete"));
        assertFalse(source.contains("from(\"direct:director-source-control-review\")"));
        assertFalse(source.contains("from(\"direct:director-project-management-review\")"));
        assertFalse(source.contains("from(\"direct:director-team-chat-review\")"));
        assertFalse(source.contains("PM/PO/DEV"));
        assertFalse(source.contains("Project Manager"));
        assertFalse(source.contains("Product Owner"));
        assertFalse(source.contains("Application Developer"));
        assertFalse(source.contains("Never create another Director run"));
    }
}
