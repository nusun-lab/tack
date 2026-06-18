package us.nusun.llmshare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.apache.camel.ProducerTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@QuarkusTest
@TestProfile(SchemaLiveTestProfile.class)
@EnabledIfSystemProperty(named = "llm.live.schema.tests", matches = "true")
class SchemaEnforcedWithoutToolsIT {
    @Inject
    ProducerTemplate producerTemplate;

    @Inject
    ObjectMapper objectMapper;

    @Test
    void returnsSchemaCompliantJsonWithoutTools() throws Exception {
        String raw = producerTemplate.requestBody(
                "direct:schema-sanity-no-tools",
                """
                Return a JSON object with:
                - result: exactly "ok"
                - evidence: exactly "no-tools"
                Return JSON only.
                """,
                String.class);

        JsonNode json = objectMapper.readTree(raw);
        assertFalse(json.isEmpty(), () -> "Expected a non-empty JSON object but got: " + raw);
        assertEquals("ok", json.path("result").asText(), () -> "Unexpected raw response: " + raw);
        assertEquals("no-tools", json.path("evidence").asText(), () -> "Unexpected raw response: " + raw);
    }
}
