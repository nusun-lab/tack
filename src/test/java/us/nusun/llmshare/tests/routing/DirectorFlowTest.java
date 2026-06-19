package us.nusun.llmshare.tests.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.camel.ProducerTemplate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DirectorFlowTest {
    @Inject
    ProducerTemplate producerTemplate;

    @Inject
    ObjectMapper objectMapper;

    @Test
    void directorRouteReturnsStructuredAssignmentsWithNormalColoredLogging() throws Exception {
        Object assignments = producerTemplate.requestBody(
                "direct:software-team-director",
                "Break this work into three role-specific objectives.",
                Object.class);

        JsonNode payload = objectMapper.valueToTree(assignments);
        assertFalse(payload.get("software-team/project-manager").asText().isBlank());
        assertFalse(payload.get("software-team/product-owner").asText().isBlank());
        assertFalse(payload.get("software-team/application-developer").asText().isBlank());
        assertTrue(payload.get("software-team/project-manager").asText().contains("Coordinate"));
        assertTrue(payload.get("software-team/product-owner").asText().contains("Clarify"));
        assertTrue(payload.get("software-team/application-developer").asText().contains("Drive"));
    }
}
