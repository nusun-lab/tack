package us.nusun.tack.framework.tests.unit;

import org.junit.jupiter.api.Test;
import us.nusun.tack.framework.tests.support.FrameworkFixtures;

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
}
