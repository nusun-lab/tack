package us.nusun.tack.framework.tests.unit;

import org.junit.jupiter.api.Test;
import us.nusun.tack.framework.inventory.ToolProfileRegistry;
import us.nusun.tack.framework.model.McpServerDefinition;
import us.nusun.tack.framework.model.ToolProfile;
import us.nusun.tack.framework.tests.support.FrameworkFixtures;

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
        assertFalse(json.contains("alpha-secret"));
        assertFalse(json.contains("beta-secret"));
        assertFalse(json.contains("gamma-secret"));
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
        assertFalse(json.contains("alpha-secret"));
        assertFalse(json.contains("beta-secret"));
        assertFalse(json.contains("gamma-secret"));
        assertFalse(json.contains("token"));
    }

    @Test
    void toolProfilePromptJsonExcludesConnectionAndAuthDetails() {
        ToolProfileRegistry registry = new ToolProfileRegistry(List.of(ToolProfile.builder("guided")
                .mcpServer(McpServerDefinition.builder(FrameworkFixtures.SERVER_GAMMA)
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
        assertTrue(json.contains(FrameworkFixtures.SERVER_GAMMA));
        assertFalse(json.contains("https://mcp.example/secret"));
        assertFalse(json.contains("X-Secret-Header"));
        assertFalse(json.contains("Bearer secret"));
        assertFalse(json.contains("x-workspace-slug"));
        assertFalse(json.contains("private-workspace"));
        assertFalse(json.contains("authHeader"));
        assertFalse(json.contains("extraHeaders"));
        assertFalse(json.contains("url"));
    }
}
