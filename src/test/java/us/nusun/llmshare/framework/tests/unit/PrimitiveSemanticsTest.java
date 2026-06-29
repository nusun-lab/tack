package us.nusun.llmshare.framework.tests.unit;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.camel.Exchange;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.state.AgentRun;
import us.nusun.llmshare.framework.model.CreateResult;
import us.nusun.llmshare.framework.model.DecisionResult;
import us.nusun.llmshare.framework.model.LlmJobRequest;
import us.nusun.llmshare.framework.model.StepResult;
import us.nusun.llmshare.framework.model.ThoughtFrame;
import us.nusun.llmshare.framework.routine.PrimitiveSupport;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;
import us.nusun.llmshare.framework.state.ThoughtStack;
import us.nusun.llmshare.framework.routine.ThoughtStackSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimitiveSemanticsTest {
    @Test
    void actRuntimeResolutionUsesRoutineDefaultsAndToolProfileProperty() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        exchange.setProperty("toolProfile", "project-management");

        support.resolveActRuntimes().process(exchange);

        assertEquals("tool-low", exchange.getProperty("resolvedToolRuntime"));
        assertEquals("synthesis-low", exchange.getProperty("resolvedSynthesisRuntime"));
        assertEquals("project-management", exchange.getProperty("toolProfile"));
    }

    @Test
    void actRuntimeResolutionAllowsPerActOverrides() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        exchange.setProperty("toolRuntime", "tool-high");
        exchange.setProperty("synthesisRuntime", "synthesis-high");

        support.resolveActRuntimes().process(exchange);

        assertEquals("tool-high", exchange.getProperty("resolvedToolRuntime"));
        assertEquals("synthesis-high", exchange.getProperty("resolvedSynthesisRuntime"));
        assertEquals("none", exchange.getProperty("toolProfile"));
    }


    @Test
    void synthesisRuntimeResolutionUsesRoutineDefaultUnlessPropertyOverridesIt() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);

        support.resolveSynthesisRuntime("synthesisRuntime").process(exchange);
        assertEquals("synthesis-low", exchange.getProperty("resolvedSynthesisRuntime"));

        exchange.setProperty("synthesisRuntime", "synthesis-high");
        support.resolveSynthesisRuntime("synthesisRuntime").process(exchange);
        assertEquals("synthesis-high", exchange.getProperty("resolvedSynthesisRuntime"));
    }

    @Test
    void markCurrentPinsPrimitiveAndPersonaIdentityOnTheExchange() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        exchange.setProperty("actId", "delivery-start");

        support.markCurrent("act", "actId").process(exchange);

        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        assertEquals("act", run.currentPrimitive());
        assertEquals("delivery-start", run.currentId());
        assertEquals(FrameworkFixtures.PERSONA_A, exchange.getProperty("personaId"));
    }


    @Test
    void toolPassRequestCarriesFrameworkMetadataAcrossQueuedBoundary() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        support.resolveActRuntimes().process(exchange);
        exchange.setProperty("actId", "assignment-review");
        support.markCurrent("act", "actId").process(exchange);
        exchange.setProperty("toolProfile", "source-control");
        exchange.setProperty("actPrompt", "Inspect assigned work.");

        support.prepareToolPassPrompt().process(exchange);

        LlmJobRequest request = exchange.getIn().getBody(LlmJobRequest.class);
        assertEquals(FrameworkFixtures.PERSONA_A, request.metadata().get("personaId"));
        assertEquals("source-control", request.metadata().get("toolProfile"));
        assertEquals("act", request.metadata().get("primitive"));
        assertEquals("assignment-review", request.metadata().get("primitiveId"));
        assertEquals("tool-low", request.metadata().get("executionRuntime"));
        assertEquals("low-pass", request.metadata().get("inferenceLane"));
        assertEquals("run-1", request.metadata().get("agentRunId"));
        assertEquals("mission-1", request.metadata().get("missionId"));
    }

    @Test
    void toolPassPromptUsesPersonaMissionThoughtsAndResolvedRuntime() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("routine", "Developer context", 100, "Repository is not confirmed yet."));
        exchange.setProperty(ThoughtStackSupport.STACK_PROPERTY, stack);
        exchange.setProperty("resolvedToolRuntime", "tool-low");
        exchange.setProperty("toolProfile", "source-control");
        exchange.setProperty("actPrompt", "Confirm the target source repository.");

        support.prepareToolPassPrompt().process(exchange);

        LlmJobRequest request = exchange.getIn().getBody(LlmJobRequest.class);
        assertEquals("tool-low", request.requester());
        assertEquals("Persona A", request.role());
        assertTrue(request.systemPrompt().contains("Traits:"));
        assertTrue(request.prompt().contains("Test mission"));
        assertTrue(request.prompt().contains("Repository is not confirmed yet."));
        assertTrue(request.prompt().contains("Confirm the target source repository."));
    }


    @Test
    void decisionPromptReadsThoughtStackAndLatestStepWithoutOpeningToolProfile() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        run.apply(new StepResult("delivery-start", "blocked", "Repository is missing.", java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), "needs-coordinator"));
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("developer", "Developer context", 100, "Need repository name before coding."));
        exchange.setProperty(ThoughtStackSupport.STACK_PROPERTY, stack);
        exchange.setProperty("resolvedSynthesisRuntime", "synthesis-high");
        exchange.setProperty("decisionId", "application-developer-done");
        exchange.setProperty("decisionPrompt", "Is the application developer routine complete?");

        support.prepareDecisionPrompt().process(exchange);

        LlmJobRequest request = exchange.getIn().getBody(LlmJobRequest.class);
        assertEquals("synthesis-high", request.requester());
        assertEquals("classpath:schemas/decision-result-schema.json", request.responseSchema());
        assertTrue(request.prompt().contains("Need repository name before coding."));
        assertTrue(request.prompt().contains("Repository is missing."));
        assertTrue(request.prompt().contains("Return a boolean result"));
    }


    @Test
    void createSchemaClasspathUriIsResolvedIntoPromptSchemaBody() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        exchange.setProperty("resolvedSynthesisRuntime", "synthesis-low");
        exchange.setProperty("createId", "initial-agent-runs");
        exchange.setProperty("createPrompt", "Propose the initial agent runs.");
        exchange.setProperty("createSchema", "classpath:schemas/framework/start-agent-run-commands-schema.json");

        support.prepareCreatePrompt().process(exchange);

        LlmJobRequest request = exchange.getIn().getBody(LlmJobRequest.class);
        assertEquals("classpath:schemas/framework/start-agent-run-commands-schema.json", request.responseSchema());
        assertTrue(exchange.getProperty("createSchemaJson", String.class).contains("agentRuns"));
        assertTrue(request.prompt().contains("\"agentRuns\""));
        assertTrue(request.prompt().contains("\"personaId\""));
    }

    @Test
    void createPromptIsGenericByDefaultAndIncludesMissionThoughtsAndCallerSchema() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("mission", "Mission planning", 200, "Need PM, PO, and developer runs."));
        exchange.setProperty(ThoughtStackSupport.STACK_PROPERTY, stack);
        exchange.setProperty("resolvedSynthesisRuntime", "synthesis-low");
        exchange.setProperty("createId", "initial-agent-runs");
        exchange.setProperty("createPrompt", "Propose the initial agent runs.");
        String schema = """
                {"type":"object","required":["agentRuns"]}
                """.strip();
        exchange.setProperty("createSchema", schema);

        support.prepareCreatePrompt().process(exchange);

        LlmJobRequest request = exchange.getIn().getBody(LlmJobRequest.class);
        assertEquals("synthesis-low", request.requester());
        assertEquals(schema, exchange.getProperty("createSchemaJson", String.class));
        assertTrue(request.prompt().contains(schema));
        assertTrue(request.prompt().contains("Test mission"));
        assertFalse(request.prompt().contains("Available personas"));
        assertFalse(request.prompt().contains("Available routines"));
        assertFalse(request.prompt().contains("routine-a"));
        assertFalse(request.prompt().contains("routine-a"));
        assertTrue(request.prompt().contains("Need PM, PO, and developer runs."));
        assertTrue(request.prompt().contains("Caller JSON schema"));
    }

    @Test
    void createPromptDoesNotInjectCatalogDataAccidentally() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        exchange.setProperty("resolvedSynthesisRuntime", "synthesis-low");
        exchange.setProperty("createId", "generic-create");
        exchange.setProperty("createPrompt", "Create a generic object.");
        exchange.setProperty("createSchema", "{\"type\":\"object\"}");

        support.prepareCreatePrompt().process(exchange);

        LlmJobRequest request = exchange.getIn().getBody(LlmJobRequest.class);
        assertFalse(request.prompt().contains("Available personas"));
        assertFalse(request.prompt().contains("Available routines"));
        assertFalse(request.prompt().contains("routine-a"));
        assertFalse(request.prompt().contains("routine-a"));
        assertTrue(request.prompt().contains("Create a generic object."));
    }

    @Test
    void createPromptIncludesCatalogDataOnlyWhenCallerComposesIt() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("mission", "Mission planning", 200, "Need PM, PO, and developer runs."));
        exchange.setProperty(ThoughtStackSupport.STACK_PROPERTY, stack);
        exchange.setProperty("resolvedSynthesisRuntime", "synthesis-low");
        exchange.setProperty("createId", "initial-agent-runs");
        exchange.setProperty("createPrompt", "Propose the initial agent runs.\nPersonas:\n"
                + FrameworkFixtures.personaCatalog().agentRunStartAssignableJson()
                + "\nRoutines:\n"
                + FrameworkFixtures.routineCatalog().startableJson()
                + "\nTool profiles:\n"
                + FrameworkFixtures.toolProfileRegistry().allJson());
        String schema = """
                {"type":"object","required":["agentRuns"]}
                """.strip();
        exchange.setProperty("createSchema", schema);

        support.prepareCreatePrompt().process(exchange);

        LlmJobRequest request = exchange.getIn().getBody(LlmJobRequest.class);
        assertTrue(request.prompt().contains("Personas:"));
        assertTrue(request.prompt().contains("Routines:"));
        assertTrue(request.prompt().contains("Tool profiles:"));
        assertTrue(request.prompt().contains("routine-a"));
        assertTrue(request.prompt().contains("routine-a"));
        assertTrue(request.prompt().contains("source-control"));
        assertTrue(request.prompt().contains(schema));
        assertTrue(request.prompt().contains("Need PM, PO, and developer runs."));
    }

    @Test
    void stepResultRewritesOnlyWritableTopThoughtByDefaultAndUpdatesAgentRun() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("parent", "Parent", 100, "old parent"));
        stack.push(new ThoughtFrame("child", "Child", 100, "old child"));
        exchange.setProperty(ThoughtStackSupport.STACK_PROPERTY, stack);
        exchange.getIn().setBody(FrameworkFixtures.llmResult("synthesis-low", """
                {"actId":"work-item-review","status":"complete","summary":"done","observations":[],"actions":[],"artifacts":[],"requestedActions":[],"thoughts":[{"id":"child","content":"new child"}],"nextStepRecommendation":"continue"}
                """));

        support.applyStepResult().process(exchange);

        StepResult result = exchange.getIn().getBody(StepResult.class);
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        assertEquals("work-item-review", result.actId());
        assertEquals("old parent", stack.frames().get(0).content());
        assertEquals("new child", stack.frames().get(1).content());
        assertSame(result, exchange.getProperty("latestStepResult"));
        assertSame(result, run.latestStepResult());
        assertEquals("done", run.latestObservation());
        assertEquals(1, run.stepHistory().size());
    }


    @Test
    void stepResultStatusControlsAgentRunStatus() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        exchange.getIn().setBody(FrameworkFixtures.llmResult("synthesis-low", """
                {"actId":"work-item-review","status":"needs-coordinator","summary":"decision needed","observations":[],"actions":[],"artifacts":[],"requestedActions":[],"thoughts":[],"nextStepRecommendation":"pause"}
                """));

        support.applyStepResult().process(exchange);

        assertEquals("needs-coordinator", exchange.getProperty("agentRun", AgentRun.class).status());
    }

    @Test
    void stepResultCanRewriteMultipleFramesWhenDepthAllowsIt() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("parent", "Parent", 100, "old parent"));
        stack.push(new ThoughtFrame("child", "Child", 100, "old child"));
        exchange.setProperty(ThoughtStackSupport.STACK_PROPERTY, stack);
        exchange.setProperty("thoughtUpdateDepth", 2);
        exchange.getIn().setBody(FrameworkFixtures.llmResult("synthesis-low", """
                {"actId":"release-checkpoint","status":"complete","summary":"done","observations":[],"actions":[],"artifacts":[],"requestedActions":[],"thoughts":[{"id":"parent","content":"new parent"},{"id":"child","content":"new child"}],"nextStepRecommendation":"continue"}
                """));

        support.applyStepResult().process(exchange);

        assertEquals("new parent", stack.frames().get(0).content());
        assertEquals("new child", stack.frames().get(1).content());
    }

    @Test
    void stepResultRejectsNonWritableThoughtUpdate() {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("parent", "Parent", 100, "old parent"));
        stack.push(new ThoughtFrame("child", "Child", 100, "old child"));
        exchange.setProperty(ThoughtStackSupport.STACK_PROPERTY, stack);
        exchange.getIn().setBody(FrameworkFixtures.llmResult("synthesis-low", """
                {"actId":"work-item-review","status":"complete","summary":"done","observations":[],"actions":[],"artifacts":[],"requestedActions":[],"thoughts":[{"id":"parent","content":"bad update"}],"nextStepRecommendation":"continue"}
                """));

        Exception failure = assertThrows(Exception.class, () -> support.applyStepResult().process(exchange));
        assertTrue(failure.getMessage().contains("non-writable thought"));
    }

    @Test
    void decisionResultStoresFullObjectAndMirrorsBooleanForCamelControlFlow() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        exchange.getIn().setBody(FrameworkFixtures.llmResult("synthesis-low", """
                {"decisionId":"application-developer-done","result":true,"confidence":0.91,"reason":"delivery state is represented","evidence":["repo found"]}
                """));

        support.applyDecisionResult().process(exchange);

        DecisionResult result = exchange.getIn().getBody(DecisionResult.class);
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        assertTrue(result.result());
        assertSame(result, exchange.getProperty("decisions.application-developer-done"));
        assertEquals(true, exchange.getProperty("decision.application-developer-done.result"));
        assertSame(result, run.latestDecisionResult());
    }


    @Test
    void createResultRejectsSchemaInvalidResponse() {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        exchange.setProperty("createId", "initial-agent-runs");
        exchange.setProperty("createSchemaJson", "{\"type\":\"object\",\"required\":[\"agentRuns\"]}");
        exchange.getIn().setBody(FrameworkFixtures.llmResult("synthesis-low", "{\"wrong\":[]}"));

        Exception failure = assertThrows(Exception.class, () -> support.applyCreateResult(true).process(exchange));

        assertTrue(failure.getMessage().contains("CreateResult schema validation failed"));
        assertTrue(failure.getMessage().contains("agentRuns"));
    }

    @Test
    void createResultEnforcesEnumAdditionalPropertiesArrayBoundsAndNestedSchemas() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        String schema = """
                {
                  "type": "object",
                  "additionalProperties": false,
                  "properties": {
                    "status": { "type": "string", "enum": ["ready", "blocked"] },
                    "items": {
                      "type": "array",
                      "minItems": 1,
                      "maxItems": 2,
                      "items": {
                        "type": "object",
                        "additionalProperties": false,
                        "required": ["name", "score"],
                        "properties": {
                          "name": { "type": "string", "pattern": "^[a-z]+$" },
                          "score": { "type": "integer", "minimum": 1, "maximum": 5 }
                        }
                      }
                    }
                  },
                  "required": ["status", "items"]
                }
                """;
        Exchange valid = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        valid.setProperty("createId", "schema-validation");
        valid.setProperty("createSchemaJson", schema);
        valid.getIn().setBody(FrameworkFixtures.llmResult("synthesis-low", "{\"status\":\"ready\",\"items\":[{\"name\":\"alpha\",\"score\":3}]}"));

        support.applyCreateResult(true).process(valid);

        assertInstanceOf(JsonNode.class, valid.getIn().getBody());

        assertCreateSchemaFails(schema, "{\"status\":\"waiting\",\"items\":[{\"name\":\"alpha\",\"score\":3}]}", "enum");
        assertCreateSchemaFails(schema, "{\"status\":\"ready\",\"extra\":true,\"items\":[{\"name\":\"alpha\",\"score\":3}]}", "additional");
        assertCreateSchemaFails(schema, "{\"status\":\"ready\",\"items\":[]}", "at least");
        assertCreateSchemaFails(schema, "{\"status\":\"ready\",\"items\":[{\"name\":\"alpha\",\"score\":3},{\"name\":\"beta\",\"score\":4},{\"name\":\"gamma\",\"score\":5}]}", "at most");
        assertCreateSchemaFails(schema, "{\"status\":\"ready\",\"items\":[{\"name\":\"Alpha\",\"score\":3}]}", "does not match");
        assertCreateSchemaFails(schema, "{\"status\":\"ready\",\"items\":[{\"name\":\"alpha\",\"score\":9}]}", "maximum");
    }

    @Test
    void failedQueuedResultsProduceMeaningfulPrimitiveFailures() {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        java.time.Instant now = java.time.Instant.now();
        exchange.getIn().setBody(new us.nusun.llmshare.framework.model.LlmJobResult(java.util.UUID.randomUUID(), us.nusun.llmshare.framework.model.LlmJobStatus.FAILED, "synthesis-low", "test", "mock", null, null, now, now, now, 0, "model unavailable"));

        Exception failure = assertThrows(Exception.class, () -> support.applyDecisionResult().process(exchange));

        assertTrue(failure.getMessage().contains("decision synthesis pass failed: model unavailable"));
        assertEquals("model unavailable", exchange.getProperty("latestPrimitiveFailure"));
    }

    @Test
    void createResultStoresSchemaShapedObjectAndCanExposeObjectAsBody() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        exchange.setProperty("createId", "initial-agent-runs");
        exchange.setProperty("schemaId", "agent-run-list");
        exchange.getIn().setBody(FrameworkFixtures.llmResult("synthesis-low", """
                {"agentRuns":[{"personaId":"routine-a","routineId":"routine-a","reason":"Implement the first vertical slice."}]}
                """));

        support.applyCreateResult(true).process(exchange);

        JsonNode body = assertInstanceOf(JsonNode.class, exchange.getIn().getBody());
        CreateResult result = exchange.getProperty("creates.initial-agent-runs", CreateResult.class);
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        assertEquals("agent-run-list", result.schemaId());
        assertEquals("routine-a", body.get("agentRuns").get(0).get("personaId").asText());
        assertSame(result, exchange.getProperty("latestCreateResult"));
        assertSame(result, run.latestCreateResult());
    }

    private static void assertCreateSchemaFails(String schema, String content, String expectedMessagePart) {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        exchange.setProperty("createId", "schema-validation");
        exchange.setProperty("createSchemaJson", schema);
        exchange.getIn().setBody(FrameworkFixtures.llmResult("synthesis-low", content));

        Exception failure = assertThrows(Exception.class, () -> support.applyCreateResult(true).process(exchange));

        assertTrue(failure.getMessage().contains("CreateResult schema validation failed"));
        assertTrue(failure.getMessage().toLowerCase().contains(expectedMessagePart.toLowerCase()), failure.getMessage());
    }
}
