package us.nusun.llmshare.tests.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import us.nusun.llmshare.model.RoutineStepResult;
import us.nusun.llmshare.model.RoutineToolTraceEntry;
import us.nusun.llmshare.tests.support.SchemaLiveTestProfile;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(SchemaLiveTestProfile.class)
@EnabledIfSystemProperty(named = "llm.live.schema.tests", matches = "true")
class RoutineStepPipelineIT {
    @Inject
    ProducerTemplate producerTemplate;

    @Test
    void routineStepUsesToolBeforeStructuredFormatting() {
        Exchange exchange = producerTemplate.request("direct:software-team-routine-step", in -> {
            in.getIn().setHeader("agent", "software-team/application-developer");
            in.getIn().setHeader("role", "application-developer");
            in.getIn().setHeader("agentLabel", "DEV");
            in.getIn().setHeader("systemPrompt", """
                    You are the Application Developer in a small software team.
                    Use available MCP tools whenever they are relevant.
                    Keep responses concise.
                    """);
            in.setProperty("objective", "Confirm whether the shared wiki repository exists.");
            in.setProperty("requestPrompt", "We need evidence that the wiki repo is reachable.");
            in.setProperty("cycleCount", 1);
            in.setProperty("currentStep", "gitea-repo-check");
            in.setProperty("currentRoutine", "Using Gitea, check whether the shared markdown wiki repo exists and is reachable.");
            in.setProperty("priorSummary", "No prior engineering summary yet.");
            in.setProperty("dayRoutineResults", "");
        });

        @SuppressWarnings("unchecked")
        List<RoutineToolTraceEntry> trace = exchange.getProperty("routineToolTrace", List.class);
        RoutineStepResult result = exchange.getProperty("routineStepResult", RoutineStepResult.class);

        assertNotNull(trace, "Expected tool trace to be captured on the exchange.");
        assertNotNull(result, "Expected structured routine step result on the exchange.");
        assertFalse(trace.isEmpty(), "Expected at least one MCP tool execution in the exploratory phase.");
        assertTrue(trace.stream().anyMatch(entry -> "gitea".equals(entry.service())),
                () -> "Expected a Gitea tool call but saw: " + trace);
        assertFalse(Boolean.TRUE.equals(exchange.getProperty("routineExplorationSchemaApplied", Boolean.class)),
                "Exploratory MCP-enabled phase must not have provider schema applied.");
        assertTrue(Boolean.TRUE.equals(exchange.getProperty("routineFormatterSchemaApplied", Boolean.class)),
                "Formatter phase should apply schema.");
        assertFalse(result.summary().isBlank());
        assertFalse(result.nextAction().isBlank());
        assertTrue(result.artifactsTouched().stream().filter(Objects::nonNull).anyMatch(value -> !value.isBlank()));
    }
}
