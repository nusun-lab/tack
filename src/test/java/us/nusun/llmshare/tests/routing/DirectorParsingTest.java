package us.nusun.llmshare.tests.routing;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.ProducerTemplate;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.model.LlmJobEnvelope;
import us.nusun.llmshare.model.LlmJobRequest;
import us.nusun.llmshare.model.LlmJobResult;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class DirectorParsingTest {
    @Inject
    ProducerTemplate producerTemplate;

    @Test
    void parsesValidDirectorJsonAssignments() {
        @SuppressWarnings("unchecked")
        Map<String, String> assignments = producerTemplate.requestBody(
                "direct:parse-director-assignments",
                succeededDirectorResult("""
                        {"software-team/project-manager":"Coordinate the team.","software-team/product-owner":"Clarify the backlog.","software-team/application-developer":"Ship the code."}
                        """),
                Map.class);

        assertEquals("Coordinate the team.", assignments.get("software-team/project-manager"));
        assertEquals("Clarify the backlog.", assignments.get("software-team/product-owner"));
        assertEquals("Ship the code.", assignments.get("software-team/application-developer"));
    }

    @Test
    void failsClearlyWhenDirectorJsonIsBlank() {
        CamelExecutionException exception = assertThrows(
                CamelExecutionException.class,
                () -> producerTemplate.requestBody(
                        "direct:parse-director-assignments",
                        succeededDirectorResult("   "))
        );

        assertEquals("Director LLM response content is empty", exception.getCause().getMessage());
    }

    @Test
    void failsClearlyWhenDirectorJsonIsNotJson() {
        CamelExecutionException exception = assertThrows(
                CamelExecutionException.class,
                () -> producerTemplate.requestBody(
                        "direct:parse-director-assignments",
                        succeededDirectorResult("DIR: not json"))
        );

        assertEquals("Director LLM response is not valid JSON", exception.getCause().getMessage());
    }

    private static LlmJobResult succeededDirectorResult(String content) {
        Instant startedAt = Instant.now();
        LlmJobEnvelope job = LlmJobEnvelope.queued(new LlmJobRequest(
                UUID.randomUUID(),
                "software-team/director",
                "director",
                0,
                "prompt",
                "system",
                "classpath:schemas/director-objectives-schema.json",
                Map.of(),
                startedAt
        ));
        return LlmJobResult.succeeded(job, "mock", content, content, startedAt);
    }
}
