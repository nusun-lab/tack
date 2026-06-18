package us.nusun.llmshare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.apache.camel.ProducerTemplate;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

@QuarkusTest
@TestProfile(SchemaLiveTestProfile.class)
@EnabledIfSystemProperty(named = "llm.live.schema.tests", matches = "true")
class SchemaEnforcedWithToolsIT {
    @Inject
    ProducerTemplate producerTemplate;

    @Inject
    ObjectMapper objectMapper;

    @Test
    void returnsSchemaCompliantJsonThroughToolEnabledAgentPath() throws Exception {
        String configuredTool = configuredDirectorTool();
        assertNotNull(configuredTool, missingConfigurationMessage());

        String raw = producerTemplate.requestBody(
                "direct:schema-sanity-with-tools",
                """
                Use the configured read-only %s tool if it is relevant.
                Then return a JSON object with:
                - result: a short status string
                - evidence: one short fact or outcome string
                Return JSON only.
                """.formatted(configuredTool),
                String.class);

        JsonNode json = objectMapper.readTree(raw);
        assertFalse(json.isEmpty(), () -> "Expected a non-empty JSON object but got: " + raw);
        assertFalse(json.path("result").asText().isBlank(), () -> "Unexpected raw response: " + raw);
        assertFalse(json.path("evidence").asText().isBlank(), () -> "Unexpected raw response: " + raw);
    }

    private static String configuredDirectorTool() {
        if (hasValue("agent.mcp.mattermost.url") && hasValue("agent.identity.director.mattermost.token")) {
            return "mattermost";
        }
        if (hasValue("agent.mcp.gitea.url") && hasValue("agent.identity.director.gitea.token")) {
            return "gitea";
        }
        if (hasValue("agent.mcp.plane.url") && hasValue("agent.identity.director.plane.token")) {
            return "plane";
        }
        return null;
    }

    private static boolean hasValue(String key) {
        return ConfigProvider.getConfig()
                .getOptionalValue(key, String.class)
                .filter(value -> !value.isBlank())
                .isPresent();
    }

    private static String missingConfigurationMessage() {
        return """
                No director MCP tool configuration was available to the test.
                Expected one of:
                - agent.mcp.mattermost.url + agent.identity.director.mattermost.token
                - agent.mcp.gitea.url + agent.identity.director.gitea.token
                - agent.mcp.plane.url + agent.identity.director.plane.token
                """;
    }
}
