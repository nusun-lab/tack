package us.nusun.llmshare.framework.tests.unit;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.camel.Exchange;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.state.AgentRun;
import us.nusun.llmshare.framework.model.CreateResult;
import us.nusun.llmshare.framework.model.DecisionResult;
import us.nusun.llmshare.framework.model.LlmJobRequest;
import us.nusun.llmshare.framework.model.LlmPassType;
import us.nusun.llmshare.framework.model.StepResult;
import us.nusun.llmshare.framework.model.ThoughtFrame;
import us.nusun.llmshare.framework.llm.ProviderStructuredOutputSupport;
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
    void actLaneResolutionUsesRoutineDefaultAndToolProfileProperty() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        exchange.setProperty("toolProfile", FrameworkFixtures.PROFILE_BETA);

        support.resolveActLane().process(exchange);

        assertEquals("low-pass", exchange.getProperty("resolvedLane"));
        assertEquals(FrameworkFixtures.PROFILE_BETA, exchange.getProperty("toolProfile"));
    }

    @Test
    void actLaneResolutionAllowsPerActOverride() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        exchange.setProperty("lane", "high-pass");

        support.resolveActLane().process(exchange);

        assertEquals("high-pass", exchange.getProperty("resolvedLane"));
        assertEquals("none", exchange.getProperty("toolProfile"));
    }


    @Test
    void laneResolutionUsesRoutineDefaultUnlessPropertyOverridesIt() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);

        support.resolveLane().process(exchange);
        assertEquals("low-pass", exchange.getProperty("resolvedLane"));

        exchange.setProperty("lane", "high-pass");
        support.resolveLane().process(exchange);
        assertEquals("high-pass", exchange.getProperty("resolvedLane"));
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
        support.resolveActLane().process(exchange);
        exchange.setProperty("actId", "assignment-review");
        support.markCurrent("act", "actId").process(exchange);
        exchange.setProperty("toolProfile", FrameworkFixtures.PROFILE_GAMMA);
        exchange.setProperty("actPrompt", "Inspect assigned work.");

        support.prepareToolPassPrompt().process(exchange);

        LlmJobRequest request = exchange.getIn().getBody(LlmJobRequest.class);
        assertEquals(FrameworkFixtures.PERSONA_A, request.metadata().get("personaId"));
        assertEquals(FrameworkFixtures.PROFILE_GAMMA, request.metadata().get("toolProfile"));
        assertEquals("act", request.metadata().get("primitive"));
        assertEquals("assignment-review", request.metadata().get("primitiveId"));
        assertEquals("low-pass", request.laneId());
        assertEquals(LlmPassType.TOOL, request.passType());
        assertEquals("low-pass", request.metadata().get("laneId"));
        assertEquals("TOOL", request.metadata().get("passType"));
        assertEquals("run-1", request.metadata().get("agentRunId"));
        assertEquals("mission-1", request.metadata().get("missionId"));
        assertFalse(request.metadata().containsKey(ProviderStructuredOutputSupport.FORCE_PROVIDER_SCHEMA_PROPERTY));
    }

    @Test
    void toolPassPromptUsesPersonaMissionThoughtsAndResolvedLane() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("routine", "dev-selection", "Developer context", 100, "Repository is not confirmed yet."));
        exchange.setProperty(ThoughtStackSupport.STACK_PROPERTY, stack);
        exchange.setProperty("resolvedLane", "low-pass");
        exchange.setProperty("toolProfile", FrameworkFixtures.PROFILE_GAMMA);
        exchange.setProperty("actPrompt", "Confirm the target source repository.");

        support.prepareToolPassPrompt().process(exchange);

        LlmJobRequest request = exchange.getIn().getBody(LlmJobRequest.class);
        assertEquals("low-pass", request.laneId());
        assertEquals(LlmPassType.TOOL, request.passType());
        assertEquals("Persona A", request.role());
        assertTrue(request.systemPrompt().contains("Traits:"));
        assertTrue(request.systemPrompt().contains("Builds framework test scenarios"));
        assertTrue(request.systemPrompt().contains("tool-backed work"));
        assertToolAccessScopeGuidance(request.systemPrompt());
        assertTrue(request.prompt().contains("Test mission"));
        assertTrue(request.prompt().contains("Test run input"));
        assertTrue(request.prompt().contains("Act id:"));
        assertTrue(request.prompt().contains("Routine context:"));
        assertTrue(request.prompt().contains("id: routine-a"));
        assertTrue(request.prompt().contains("Mission roster:"));
        assertTrue(request.prompt().contains("- name: Persona A"));
        assertTrue(request.prompt().contains("routine: Routine A"));
        assertTrue(request.prompt().contains("platform identities: alpha-mcp @persona-a, beta-mcp @persona-a, gamma-mcp @persona-a"));
        assertTrue(request.prompt().contains("personaId: persona-a"));
        assertTrue(request.prompt().contains("routineId: routine-a"));
        assertTrue(request.prompt().contains("agentRunId: run-1"));
        assertTrue(request.prompt().contains("status: running"));
        assertTrue(request.prompt().contains("routine-a"));
        assertFalse(request.prompt().contains("\"self"));
        assertFalse(request.prompt().contains("Persona B"));
        assertFalse(request.prompt().contains("Credentials/tokens"));
        assertFalse(request.prompt().contains("alpha-secret"));
        assertFalse(request.prompt().contains("beta-secret"));
        assertFalse(request.prompt().contains("gamma-secret"));
        assertTrue(request.prompt().contains("Tool profile: " + FrameworkFixtures.PROFILE_GAMMA));
        assertTrue(request.prompt().contains("Read before writing and avoid duplicate branches or pull requests."));
        assertTrue(request.prompt().contains("name: dev-selection"));
        assertTrue(request.prompt().contains("purpose: Developer context"));
        assertTrue(request.prompt().contains("maxTokens: 100"));
        assertTrue(request.prompt().contains("updatedAt:"));
        assertTrue(request.prompt().contains("Repository is not confirmed yet."));
        assertTrue(request.prompt().contains("Confirm the target source repository."));
        assertTrue(request.prompt().contains("Outcome:"));
        assertTrue(request.prompt().contains("Evidence / links / ids:"));
        assertTrue(request.prompt().contains("Label links/ids as verified, missing, expected, or reference"));
        assertTrue(request.prompt().contains("Do not include guessed URLs"));
        assertTrue(request.prompt().contains("Latest observations from this act:"));
        assertTrue(request.prompt().contains("- created:"));
        assertTrue(request.prompt().contains("- verified:"));
        assertTrue(request.prompt().contains("- still missing:"));
        assertTrue(request.prompt().contains("- blockers remaining:"));
        assertTrue(request.prompt().contains("- stale blockers resolved:"));
        assertTrue(request.prompt().contains("Durable facts to remember:"));
        assertTrue(request.prompt().contains("record missing/expected artifacts as blockers without fake URLs"));
        assertTrue(request.prompt().contains("label reference examples as reference/patterns"));
        assertFalse(request.prompt().contains("Next useful action:"));
        assertFalse(request.prompt().contains("Return a concise factual pass result"));
    }

    @Test
    void stepSynthesisRequestForcesProviderSchema() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("developer", "Developer context", 100, "Need current work summary."));
        exchange.setProperty(ThoughtStackSupport.STACK_PROPERTY, stack);
        exchange.setProperty("resolvedLane", "low-pass");
        exchange.setProperty("actId", "chat-kickoff");
        exchange.setProperty("actPrompt", "Use synthetic tools to inspect repository status before implementation.");
        exchange.setProperty("toolPassObservedAt", java.time.Instant.parse("2026-07-07T12:00:00Z"));
        exchange.setProperty("toolPassResult", "No stale focus post found.");

        support.prepareStepSynthesisPrompt().process(exchange);

        LlmJobRequest request = exchange.getIn().getBody(LlmJobRequest.class);
        assertEquals("classpath:schemas/step-result-schema.json", request.responseSchema());
        assertEquals(true, request.metadata().get(ProviderStructuredOutputSupport.FORCE_PROVIDER_SCHEMA_PROPERTY));
        assertTrue(request.systemPrompt().contains("Builds framework test scenarios"));
        assertTrue(request.systemPrompt().contains("Synthesize framework action results."));
        assertTrue(request.systemPrompt().contains("Return only valid JSON object matching the supplied schema."));
        assertTrue(request.systemPrompt().contains("This pass has no tools; do not call tools, reason about tool availability, or retry the action."));
        assertTrue(request.systemPrompt().contains("The preceding action has already completed; do not repeat it."));
        assertTrue(request.systemPrompt().contains("Treat the supplied latest observation as the newest evidence."));
        assertTrue(request.systemPrompt().contains("Rewrite thoughts as current-state snapshots."));
        assertNoToolAccessScopeGuidance(request.systemPrompt());
        assertTrue(request.prompt().contains("Test run input"));
        assertTrue(request.prompt().contains("Preceding action objective, for context only:"));
        assertTrue(request.prompt().contains("Use synthetic tools to inspect repository status before implementation."));
        assertTrue(request.prompt().contains("Do not execute or repeat this objective during synthesis."));
        assertFalse(request.prompt().contains("Act prompt:"));
        assertTrue(request.prompt().contains("Latest observation observedAt: 2026-07-07T12:00:00Z"));
    }

    @Test
    void stepSynthesisPromptExposesAllThoughtsForUpdates() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("parent-thought", "Parent context", 100, "Older stable context."));
        stack.push(new ThoughtFrame("child-thought", "Child context", 100, ""));
        exchange.setProperty(ThoughtStackSupport.STACK_PROPERTY, stack);
        exchange.setProperty("resolvedLane", "low-pass");
        exchange.setProperty("actId", "chat-kickoff");
        exchange.setProperty("actPrompt", "Post implementation focus if stale.");
        exchange.setProperty("toolPassResult", "No stale focus post found.");

        support.prepareStepSynthesisPrompt().process(exchange);

        LlmJobRequest request = exchange.getIn().getBody(LlmJobRequest.class);
        String prompt = request.prompt();
        assertFalse(prompt.contains("Active thoughts:"));
        assertTrue(prompt.contains("Preceding action objective, for context only:"));
        assertTrue(prompt.contains("Post implementation focus if stale."));
        assertTrue(prompt.contains("Do not execute or repeat this objective during synthesis."));
        assertFalse(prompt.contains("Act prompt:"));
        assertTrue(prompt.contains("Previous thought snapshots, possibly stale:"));
        assertTrue(prompt.contains("parent-thought"));
        assertTrue(prompt.contains("child-thought"));
        assertTrue(prompt.contains("Return one StepResult JSON object."));
        assertTrue(prompt.contains("Thought synthesis:"));
        assertTrue(prompt.contains("Treat previous thoughts as stale snapshots"));
        assertTrue(prompt.contains("latest observation as the newest state"));
        assertTrue(prompt.contains("latest observation wins"));
        assertTrue(prompt.contains("summary, artifacts, and thoughts describe the same current state"));
        assertTrue(prompt.contains("Replace stale missing or blocker details"));
        assertTrue(prompt.contains("Thought updates:"));
        assertTrue(prompt.contains("include every thought id whose durable memory changed"));
        assertTrue(prompt.contains("Only use thought ids listed in the previous thought snapshots"));
        assertTrue(prompt.contains("Content must be non-empty and within maxTokens"));
        assertFalse(prompt.contains("next actions"));
        assertTrue(prompt.contains("Artifact accuracy:"));
        assertTrue(prompt.contains("Use URLs only when verified"));
        assertTrue(prompt.contains("without invented URLs"));
        assertTrue(prompt.contains("Do not promote reference artifacts"));
        assertTrue(prompt.indexOf("Thought synthesis:") < prompt.indexOf("Thought updates:"));
        assertTrue(prompt.indexOf("Thought updates:") < prompt.indexOf("Artifact accuracy:"));
        assertFalse(prompt.contains("Good memory shape"));
        assertFalse(prompt.contains("current wiki: missing"));
        assertFalse(prompt.contains("project tracker verified; project membership"));
    }

    @Test
    void stepSynthesisPromptFramesVerifiedAnchorAsPatchOverStaleThoughtSnapshot() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("pm-root", "project-manager-routine", "Durable PM project anchors", 300,
                "Synthetic project tracker missing"));
        exchange.setProperty(ThoughtStackSupport.STACK_PROPERTY, stack);
        exchange.setProperty("resolvedLane", "low-pass");
        exchange.setProperty("actId", "synthetic-planning-setup");
        exchange.setProperty("actPrompt", "Create or verify the project tracker for the mission.");
        exchange.setProperty("toolPassObservedAt", java.time.Instant.parse("2026-07-07T12:00:00Z"));
        exchange.setProperty("toolPassResult", """
                Outcome: Project tracker created and verified.
                Evidence / links / ids:
                - verified: Synthetic project Repair Shop Queue App, id 36a6bccc-4729-4c2d-83fc-0fe48441b1bd, identifier RSQA, workspace 7a3f3b43-ef50-476b-b316-bc1bf5021393, status active/verified
                Latest observations from this act:
                - created: Synthetic project Repair Shop Queue App
                - verified: id=36a6bccc-4729-4c2d-83fc-0fe48441b1bd; identifier=RSQA; workspace=7a3f3b43-ef50-476b-b316-bc1bf5021393; status=active/verified
                - still missing: none
                - blockers remaining: none for project tracker
                - stale blockers resolved: Synthetic project tracker missing -> resolved; project RSQA verified
                Durable facts to remember: Synthetic project Repair Shop Queue App (RSQA) verified.
                """);

        support.prepareStepSynthesisPrompt().process(exchange);

        String prompt = exchange.getIn().getBody(LlmJobRequest.class).prompt();
        assertTrue(prompt.contains("Previous thought snapshots, possibly stale"));
        assertTrue(prompt.contains("Synthetic project tracker missing"));
        assertTrue(prompt.contains("Latest observation from this act:"));
        assertTrue(prompt.contains("36a6bccc-4729-4c2d-83fc-0fe48441b1bd"));
        assertTrue(prompt.contains("RSQA"));
        assertTrue(prompt.contains("7a3f3b43-ef50-476b-b316-bc1bf5021393"));
        assertTrue(prompt.contains("status active/verified"));
        assertTrue(prompt.contains("stale blockers resolved: Synthetic project tracker missing -> resolved; project RSQA verified"));
        assertTrue(prompt.contains("Treat previous thoughts as stale snapshots"));
        assertTrue(prompt.contains("latest observation as the newest state"));
        assertTrue(prompt.contains("latest observation wins"));
        assertTrue(prompt.contains("Replace stale missing or blocker details"));
        assertTrue(prompt.contains("summary, artifacts, and thoughts describe the same current state"));
        assertTrue(prompt.indexOf("Latest observation from this act:") < prompt.indexOf("Previous thought snapshots, possibly stale"));
        assertTrue(prompt.indexOf("Thought synthesis:") < prompt.indexOf("Thought updates:"));
    }

    @Test
    void stepSynthesisPromptExposesRoutineAndLoopThoughtsByDefault() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("routine-thought", "Routine context", 100, "Broad state."));
        stack.push(new ThoughtFrame("loop-thought", "Loop context", 100, "Local state."));
        exchange.setProperty(ThoughtStackSupport.STACK_PROPERTY, stack);
        exchange.setProperty("resolvedLane", "low-pass");
        exchange.setProperty("actId", "loop-closeout");
        exchange.setProperty("actPrompt", "Close out the current loop.");
        exchange.setProperty("toolPassResult", "Loop status was updated.");

        support.prepareStepSynthesisPrompt().process(exchange);

        String prompt = exchange.getIn().getBody(LlmJobRequest.class).prompt();
        assertTrue(prompt.contains("routine-thought"));
        assertTrue(prompt.contains("loop-thought"));
    }


    @Test
    void decisionPromptReadsThoughtStackAndLatestStepWithoutOpeningToolProfile() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        run.apply(new StepResult("delivery-start", "Repository is missing.", java.util.List.of(), java.util.List.of()));
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("developer", "Developer context", 100, "Need repository name before coding."));
        exchange.setProperty(ThoughtStackSupport.STACK_PROPERTY, stack);
        exchange.setProperty("resolvedLane", "high-pass");
        exchange.setProperty("decisionId", "application-developer-done");
        exchange.setProperty("decisionPrompt", "Is the application developer routine complete?");

        support.prepareDecisionPrompt().process(exchange);

        LlmJobRequest request = exchange.getIn().getBody(LlmJobRequest.class);
        assertEquals("high-pass", request.laneId());
        assertEquals(LlmPassType.SYNTHESIS, request.passType());
        assertEquals("classpath:schemas/decision-result-schema.json", request.responseSchema());
        assertEquals(true, request.metadata().get(ProviderStructuredOutputSupport.FORCE_PROVIDER_SCHEMA_PROPERTY));
        assertTrue(request.systemPrompt().contains("Builds framework test scenarios"));
        assertTrue(request.systemPrompt().contains("DecisionResult JSON object"));
        assertNoToolAccessScopeGuidance(request.systemPrompt());
        assertTrue(request.prompt().contains("Test run input"));
        assertTrue(request.prompt().contains("Need repository name before coding."));
        assertTrue(request.prompt().contains("Repository is missing."));
        assertTrue(request.prompt().contains("Return only decisionId, result, confidence, and reason."));
    }


    @Test
    void createSchemaClasspathUriIsResolvedIntoPromptSchemaBody() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        exchange.setProperty("resolvedLane", "low-pass");
        exchange.setProperty("createId", "planned-agent-runs");
        exchange.setProperty("createPrompt", "Propose the initial agent runs.");
        exchange.setProperty("createSchema", "classpath:schemas/example/software-team/agent-run-requests-schema.json");

        support.prepareCreatePrompt().process(exchange);

        LlmJobRequest request = exchange.getIn().getBody(LlmJobRequest.class);
        assertEquals("classpath:schemas/example/software-team/agent-run-requests-schema.json", request.responseSchema());
        assertEquals(true, request.metadata().get(ProviderStructuredOutputSupport.FORCE_PROVIDER_SCHEMA_PROPERTY));
        assertTrue(request.systemPrompt().contains("Builds framework test scenarios"));
        assertTrue(request.systemPrompt().contains("caller-schema-shaped JSON"));
        assertNoToolAccessScopeGuidance(request.systemPrompt());
        assertTrue(request.prompt().contains("Test run input"));
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
        exchange.setProperty("resolvedLane", "low-pass");
        exchange.setProperty("createId", "planned-agent-runs");
        exchange.setProperty("createPrompt", "Propose the initial agent runs.");
        String schema = """
                {"type":"object","required":["agentRuns"]}
                """.strip();
        exchange.setProperty("createSchema", schema);

        support.prepareCreatePrompt().process(exchange);

        LlmJobRequest request = exchange.getIn().getBody(LlmJobRequest.class);
        assertEquals("low-pass", request.laneId());
        assertEquals(LlmPassType.SYNTHESIS, request.passType());
        assertEquals(schema, exchange.getProperty("createSchemaJson", String.class));
        assertTrue(request.prompt().contains(schema));
        assertTrue(request.prompt().contains("Test mission"));
        assertFalse(request.prompt().contains("Available personas"));
        assertFalse(request.prompt().contains("Available routines"));
        assertTrue(request.prompt().contains("Routine context:"));
        assertTrue(request.prompt().contains("id: routine-a"));
        assertTrue(request.prompt().contains("Mission roster:"));
        assertTrue(request.prompt().contains("- name: Persona A"));
        assertTrue(request.prompt().contains("routine: Routine A"));
        assertTrue(request.prompt().contains("platform identities: alpha-mcp @persona-a, beta-mcp @persona-a, gamma-mcp @persona-a"));
        assertTrue(request.prompt().contains("personaId: persona-a"));
        assertTrue(request.prompt().contains("routineId: routine-a"));
        assertTrue(request.prompt().contains("agentRunId: run-1"));
        assertTrue(request.prompt().contains("status: running"));
        assertFalse(request.prompt().contains("routine-b"));
        assertTrue(request.prompt().contains("Need PM, PO, and developer runs."));
        assertTrue(request.prompt().contains("Caller JSON schema"));
    }

    @Test
    void createPromptDoesNotInjectCatalogDataAccidentally() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        exchange.setProperty("resolvedLane", "low-pass");
        exchange.setProperty("createId", "generic-create");
        exchange.setProperty("createPrompt", "Create a generic object.");
        exchange.setProperty("createSchema", "{\"type\":\"object\"}");

        support.prepareCreatePrompt().process(exchange);

        LlmJobRequest request = exchange.getIn().getBody(LlmJobRequest.class);
        assertFalse(request.prompt().contains("Available personas"));
        assertFalse(request.prompt().contains("Available routines"));
        assertTrue(request.prompt().contains("Routine context:"));
        assertTrue(request.prompt().contains("id: routine-a"));
        assertFalse(request.prompt().contains("routine-b"));
        assertTrue(request.prompt().contains("Create a generic object."));
    }

    @Test
    void createPromptIncludesCatalogDataOnlyWhenCallerComposesIt() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("mission", "Mission planning", 200, "Need PM, PO, and developer runs."));
        exchange.setProperty(ThoughtStackSupport.STACK_PROPERTY, stack);
        exchange.setProperty("resolvedLane", "low-pass");
        exchange.setProperty("createId", "planned-agent-runs");
        exchange.setProperty("createPrompt", "Propose the initial agent runs.\nPersonas:\n"
                + FrameworkFixtures.personaCatalog().agentRunStartAssignablePromptJson()
                + "\nRoutines:\n"
                + FrameworkFixtures.routineCatalog().startableJson()
                + "\nTool profiles:\n"
                + FrameworkFixtures.toolProfileRegistry().promptJson());
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
        assertTrue(request.prompt().contains(FrameworkFixtures.PROFILE_GAMMA));
        assertTrue(request.prompt().contains(schema));
        assertTrue(request.prompt().contains("Need PM, PO, and developer runs."));
    }

    @Test
    void stepResultSchemaDocumentsVerifiedUrlsAndMissingArtifactsWithoutUrl() throws Exception {
        String schema = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/schemas/step-result-schema.json"));

        assertTrue(schema.contains("Use url only for verified URLs"));
        assertTrue(schema.contains("Missing expected artifacts can be represented without url"));
        assertTrue(schema.contains("status: missing"));
        assertTrue(schema.contains("Label reference examples as reference/patterns"));
    }

    @Test
    void stepResultArtifactsCanRepresentMissingWithoutUrlAndVerifiedWithUrl() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        exchange.getIn().setBody(FrameworkFixtures.llmResult("low-pass", """
                {
                  "actId":"artifact-check",
                  "summary":"Current wiki is missing; reference pattern inspected separately.",
                  "artifacts":[
                    {"name":"current wiki","status":"missing","blocker":true,"notes":"URL not verified; do not invent one"},
                    {"name":"reference pattern wiki","status":"reference","url":"https://docs.example/reference-wiki"}
                  ],
                  "thoughts":[]
                }
                """));

        support.applyStepResult().process(exchange);

        StepResult result = exchange.getIn().getBody(StepResult.class);
        java.util.Map<String, Object> missing = result.artifacts().get(0);
        java.util.Map<String, Object> verified = result.artifacts().get(1);
        assertEquals("missing", missing.get("status"));
        assertEquals(true, missing.get("blocker"));
        assertFalse(missing.containsKey("url"));
        assertEquals("reference", verified.get("status"));
        assertEquals("https://docs.example/reference-wiki", verified.get("url"));
    }

    @Test
    void stepResultRewritesRoutineAndLoopThoughtsByDefaultAndUpdatesAgentRun() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("parent", "Parent", 100, "old parent"));
        stack.push(new ThoughtFrame("child", "Child", 100, "old child"));
        exchange.setProperty(ThoughtStackSupport.STACK_PROPERTY, stack);
        exchange.getIn().setBody(FrameworkFixtures.llmResult("low-pass", """
                {"actId":"work-item-review","summary":"done","artifacts":[],"thoughts":[{"id":"parent","content":"new parent"},{"id":"child","content":"new child"}]}
                """));

        support.applyStepResult().process(exchange);

        StepResult result = exchange.getIn().getBody(StepResult.class);
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        assertEquals("work-item-review", result.actId());
        assertEquals("new parent", stack.frames().get(0).content());
        assertEquals("new child", stack.frames().get(1).content());
        assertSame(result, exchange.getProperty("latestStepResult"));
        assertSame(result, run.latestStepResult());
        assertEquals("done", run.latestObservation());
        assertEquals(1, run.stepHistory().size());
    }


    @Test
    void stepResultDoesNotControlAgentLifecycleStatus() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        exchange.getIn().setBody(FrameworkFixtures.llmResult("low-pass", """
                {"actId":"work-item-review","summary":"decision needed","artifacts":[],"thoughts":[]}
                """));

        support.applyStepResult().process(exchange);

        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        assertEquals(AgentRun.STATUS_RUNNING, run.status());
        assertEquals("decision needed", run.latestStepResult().summary());
    }

    @Test
    void stepResultAllowsEmptyThoughtUpdatesForTrueNoOpPasses() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("child", "Child", 100, "old child"));
        exchange.setProperty(ThoughtStackSupport.STACK_PROPERTY, stack);
        exchange.getIn().setBody(FrameworkFixtures.llmResult("low-pass", """
                {"actId":"noop","summary":"nothing changed","artifacts":[],"thoughts":[]}
                """));

        support.applyStepResult().process(exchange);

        assertEquals("old child", stack.frames().getFirst().content());
    }

    @Test
    void stepResultRejectsBlankThoughtUpdateWithoutWipingExistingContent() {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("child", "Child", 100, "old child"));
        exchange.setProperty(ThoughtStackSupport.STACK_PROPERTY, stack);
        exchange.getIn().setBody(FrameworkFixtures.llmResult("low-pass", """
                {"actId":"work-item-review","summary":"done","artifacts":[],"thoughts":[{"id":"child","content":"   "}]}
                """));

        Exception failure = assertThrows(Exception.class, () -> support.applyStepResult().process(exchange));

        assertTrue(failure.getMessage().contains("blank content"));
        assertEquals("old child", stack.frames().getFirst().content());
    }

    @Test
    void stepResultRejectsUnknownThoughtUpdate() {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("parent", "Parent", 100, "old parent"));
        exchange.setProperty(ThoughtStackSupport.STACK_PROPERTY, stack);
        exchange.getIn().setBody(FrameworkFixtures.llmResult("low-pass", """
                {"actId":"work-item-review","summary":"done","artifacts":[],"thoughts":[{"id":"missing","content":"bad update"}]}
                """));

        Exception failure = assertThrows(Exception.class, () -> support.applyStepResult().process(exchange));

        assertTrue(failure.getMessage().contains("unknown thought"));
        assertEquals("old parent", stack.frames().getFirst().content());
    }

    @Test
    void decisionResultStoresFullObjectAndMirrorsBooleanForCamelControlFlow() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        exchange.getIn().setBody(FrameworkFixtures.llmResult("low-pass", """
                {"decisionId":"application-developer-done","result":true,"confidence":0.91,"reason":"delivery state is represented"}
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
        exchange.setProperty("createId", "planned-agent-runs");
        exchange.setProperty("createSchemaJson", "{\"type\":\"object\",\"required\":[\"agentRuns\"]}");
        exchange.getIn().setBody(FrameworkFixtures.llmResult("low-pass", "{\"wrong\":[]}"));

        Exception failure = assertThrows(Exception.class, () -> support.applyCreateResult().process(exchange));

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
        valid.getIn().setBody(FrameworkFixtures.llmResult("low-pass", "{\"status\":\"ready\",\"items\":[{\"name\":\"alpha\",\"score\":3}]}"));

        support.applyCreateResult().process(valid);

        assertEquals("{\"status\":\"ready\",\"items\":[{\"name\":\"alpha\",\"score\":3}]}", valid.getIn().getBody(String.class));

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
        exchange.getIn().setBody(new us.nusun.llmshare.framework.model.LlmJobResult(java.util.UUID.randomUUID(), us.nusun.llmshare.framework.model.LlmJobStatus.FAILED, "low-pass", LlmPassType.SYNTHESIS, "test", "test", "mock", null, null, now, now, now, 0, "model unavailable"));

        Exception failure = assertThrows(Exception.class, () -> support.applyDecisionResult().process(exchange));

        assertTrue(failure.getMessage().contains("decision synthesis pass failed: model unavailable"));
        assertEquals("model unavailable", exchange.getProperty("latestPrimitiveFailure"));
    }

    @Test
    void createResultStoresSchemaShapedObjectAndExposesJsonAsBody() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        exchange.setProperty("createId", "planned-agent-runs");
        exchange.setProperty("schemaId", "agent-run-list");
        exchange.getIn().setBody(FrameworkFixtures.llmResult("low-pass", """
                {"agentRuns":[{"personaId":"routine-a","routineId":"routine-a","reason":"Implement the first vertical slice."}]}
                """));

        support.applyCreateResult().process(exchange);

        String body = exchange.getIn().getBody(String.class);
        CreateResult result = exchange.getProperty("creates.planned-agent-runs", CreateResult.class);
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        assertEquals("agent-run-list", result.schemaId());
        assertEquals("routine-a", result.object().get("agentRuns").get(0).get("personaId").asText());
        assertEquals("{\"agentRuns\":[{\"personaId\":\"routine-a\",\"routineId\":\"routine-a\",\"reason\":\"Implement the first vertical slice.\"}]}", body);
        assertSame(result, exchange.getProperty("latestCreateResult"));
        assertSame(result, run.latestCreateResult());
    }

    private static void assertToolAccessScopeGuidance(String systemPrompt) {
        assertTrue(systemPrompt.contains("Tool access:"));
        assertTrue(systemPrompt.contains("You have access to only 1 tool profile at a time"));
        assertTrue(systemPrompt.contains("you will have access in later steps"));
        assertFalse(systemPrompt.contains("next actions"));
    }

    private static void assertNoToolAccessScopeGuidance(String systemPrompt) {
        assertFalse(systemPrompt.contains("Tool access:"));
        assertFalse(systemPrompt.contains("Use only tools available in this pass."));
        assertFalse(systemPrompt.contains("Treat missing tools as deferred work"));
        assertFalse(systemPrompt.contains("Record blockers only from tool results"));
    }

    private static void assertCreateSchemaFails(String schema, String content, String expectedMessagePart) {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        exchange.setProperty("createId", "schema-validation");
        exchange.setProperty("createSchemaJson", schema);
        exchange.getIn().setBody(FrameworkFixtures.llmResult("low-pass", content));

        Exception failure = assertThrows(Exception.class, () -> support.applyCreateResult().process(exchange));

        assertTrue(failure.getMessage().contains("CreateResult schema validation failed"));
        assertTrue(failure.getMessage().toLowerCase().contains(expectedMessagePart.toLowerCase()), failure.getMessage());
    }
}
