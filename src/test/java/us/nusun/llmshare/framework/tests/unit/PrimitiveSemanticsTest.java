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
        assertEquals("tool-low", request.metadata().get("executionRuntimeId"));
        assertEquals("low-pass", request.metadata().get("inferenceLane"));
        assertEquals("run-1", request.metadata().get("agentRunId"));
        assertEquals("mission-1", request.metadata().get("missionId"));
        assertFalse(request.metadata().containsKey(ProviderStructuredOutputSupport.FORCE_PROVIDER_SCHEMA_PROPERTY));
    }

    @Test
    void toolPassPromptUsesPersonaMissionThoughtsAndResolvedRuntime() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("routine", "dev-selection", "Developer context", 100, "Repository is not confirmed yet."));
        exchange.setProperty(ThoughtStackSupport.STACK_PROPERTY, stack);
        exchange.setProperty("resolvedToolRuntime", "tool-low");
        exchange.setProperty("toolProfile", "source-control");
        exchange.setProperty("actPrompt", "Confirm the target source repository.");

        support.prepareToolPassPrompt().process(exchange);

        LlmJobRequest request = exchange.getIn().getBody(LlmJobRequest.class);
        assertEquals("tool-low", request.requester());
        assertEquals("Persona A", request.role());
        assertTrue(request.systemPrompt().contains("Traits:"));
        assertTrue(request.systemPrompt().contains("Builds framework test scenarios"));
        assertTrue(request.systemPrompt().contains("tool-backed work"));
        assertTrue(request.prompt().contains("Test mission"));
        assertTrue(request.prompt().contains("Test run input"));
        assertTrue(request.prompt().contains("Act id:"));
        assertTrue(request.prompt().contains("Routine context:"));
        assertTrue(request.prompt().contains("id: routine-a"));
        assertTrue(request.prompt().contains("Mission roster:"));
        assertTrue(request.prompt().contains("@persona-a"));
        assertTrue(request.prompt().contains("run-1"));
        assertTrue(request.prompt().contains("routine-a"));
        assertTrue(request.prompt().contains("\"self"));
        assertFalse(request.prompt().contains("Persona B"));
        assertTrue(request.prompt().contains("Credentials/tokens are never shown"));
        assertFalse(request.prompt().contains("mattermost-token"));
        assertFalse(request.prompt().contains("gitea-token"));
        assertFalse(request.prompt().contains("plane-token"));
        assertTrue(request.prompt().contains("Tool profile: source-control"));
        assertTrue(request.prompt().contains("Read before writing and avoid duplicate branches or pull requests."));
        assertTrue(request.prompt().contains("name: dev-selection"));
        assertTrue(request.prompt().contains("purpose: Developer context"));
        assertTrue(request.prompt().contains("maxTokens: 100"));
        assertTrue(request.prompt().contains("updatedAt:"));
        assertTrue(request.prompt().contains("Repository is not confirmed yet."));
        assertTrue(request.prompt().contains("there are no tools for reading or writing thoughts"));
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
        assertFalse(request.prompt().contains("Return a concise factual pass result"));
    }

    @Test
    void stepSynthesisRequestForcesProviderSchema() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("developer", "Developer context", 100, "Need current work summary."));
        exchange.setProperty(ThoughtStackSupport.STACK_PROPERTY, stack);
        exchange.setProperty("resolvedSynthesisRuntime", "synthesis-low");
        exchange.setProperty("actId", "chat-kickoff");
        exchange.setProperty("actPrompt", "Post implementation focus if stale.");
        exchange.setProperty("toolPassObservedAt", java.time.Instant.parse("2026-07-07T12:00:00Z"));
        exchange.setProperty("toolPassResult", "No stale focus post found.");

        support.prepareStepSynthesisPrompt().process(exchange);

        LlmJobRequest request = exchange.getIn().getBody(LlmJobRequest.class);
        assertEquals("classpath:schemas/step-result-schema.json", request.responseSchema());
        assertEquals(true, request.metadata().get(ProviderStructuredOutputSupport.FORCE_PROVIDER_SCHEMA_PROPERTY));
        assertTrue(request.systemPrompt().contains("Builds framework test scenarios"));
        assertTrue(request.systemPrompt().contains("StepResult JSON object"));
        assertTrue(request.prompt().contains("Test run input"));
        assertTrue(request.prompt().contains("Latest observation observedAt: 2026-07-07T12:00:00Z"));
    }

    @Test
    void stepSynthesisPromptOnlyExposesWritableThoughtsForUpdates() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("readonly-thought", "Read-only context", 100, "Older stable context."));
        stack.push(new ThoughtFrame("writable-thought", "Writable context", 100, ""));
        exchange.setProperty(ThoughtStackSupport.STACK_PROPERTY, stack);
        exchange.setProperty("resolvedSynthesisRuntime", "synthesis-low");
        exchange.setProperty("thoughtUpdateDepth", 1);
        exchange.setProperty("actId", "chat-kickoff");
        exchange.setProperty("actPrompt", "Post implementation focus if stale.");
        exchange.setProperty("toolPassResult", "No stale focus post found.");

        support.prepareStepSynthesisPrompt().process(exchange);

        LlmJobRequest request = exchange.getIn().getBody(LlmJobRequest.class);
        String prompt = request.prompt();
        int writableStart = prompt.indexOf("Previous writable thought snapshots, possibly stale (only these ids may be changed):");
        assertFalse(prompt.contains("Active thoughts:"));
        assertTrue(prompt.contains("Previous read-only thought snapshots, possibly stale (context only; do not include these ids in StepResult.thoughts):"));
        assertTrue(prompt.contains("readonly-thought"));
        assertTrue(writableStart >= 0);
        String writableSection = prompt.substring(writableStart);
        assertTrue(writableSection.contains("writable-thought"));
        assertFalse(writableSection.contains("readonly-thought"));
        assertFalse(writableSection.contains("writable:"));
        assertTrue(prompt.contains("rewritten as the whole compact current memory"));
        assertTrue(prompt.contains("Preserve useful mission facts"));
        assertTrue(prompt.contains("Stay within each thought's maxTokens budget"));
        assertTrue(prompt.contains("content must be non-empty, useful"));
        assertTrue(prompt.contains("There are no tools for reading or writing thoughts"));
        assertTrue(prompt.contains("Thought updates must be synthesized from the latest observation, previous thought snapshots, and prompt context"));
        assertTrue(prompt.contains("not from tool calls"));
        assertTrue(prompt.contains("Thought synthesis is a state-reduction task."));
        assertTrue(prompt.contains("Previous thoughts are not authoritative; they are stale snapshots to patch."));
        assertTrue(prompt.contains("The latest observation from this act is newer than all previous thought snapshots."));
        assertTrue(prompt.contains("Before writing StepResult.thoughts:"));
        assertTrue(prompt.contains("Extract current anchors from the previous thought snapshots."));
        assertTrue(prompt.contains("Extract new observations from the latest observation from this act."));
        assertTrue(prompt.contains("Apply the latest observation as a patch over the previous thought state."));
        assertTrue(prompt.contains("replace the old thought state with the newest observed state."));
        assertTrue(prompt.contains("remove older missing/not verified/blocker text for that same anchor."));
        assertTrue(prompt.contains("Preserve unrelated anchors that the latest observation did not change."));
        assertTrue(prompt.contains("new current-state snapshots, not as copied previous prose."));
        assertTrue(prompt.contains("summary, artifacts, and thoughts must describe the same current state"));
        assertTrue(prompt.indexOf("Thought synthesis is a state-reduction task.") < prompt.indexOf("Thought update rules:"));
        assertTrue(prompt.contains("plain-text pass result is not durable memory"));
        assertTrue(prompt.contains("most-specific writable thought is blank"));
        assertTrue(prompt.contains("shared documentation links/page paths"));
        assertTrue(prompt.contains("source repository URLs or clone URLs"));
        assertTrue(prompt.contains("collaboration channel names/links"));
        assertTrue(prompt.contains("project/work tracker ids"));
        assertTrue(prompt.contains("canonical documentation pages"));
        assertTrue(prompt.contains("selected tasks/product items/work items"));
        assertTrue(prompt.contains("blocker owners"));
        assertTrue(prompt.contains("Only record URLs, ids, and links that came from tool output, durable documentation, or prior verified thoughts"));
        assertTrue(prompt.contains("Do not invent URLs for missing artifacts"));
        assertTrue(prompt.contains("URL not verified"));
        assertTrue(prompt.contains("Reference examples or pattern artifacts from other projects must be labeled as references/patterns"));
        assertTrue(prompt.contains("Do not promote a reference/example repository"));
        assertTrue(prompt.contains("current wiki: missing"));
        assertTrue(prompt.contains("reference pattern:"));
        assertTrue(prompt.contains("`url` is only for verified URLs"));
        assertTrue(prompt.contains("status: missing"));
        assertTrue(prompt.contains("without an invented URL"));
        assertTrue(prompt.contains("Freshness rules:"));
        assertTrue(prompt.contains("Thoughts are current-state snapshots, not append-only logs"));
        assertTrue(prompt.contains("Each thought `updatedAt` is when that memory snapshot was last rewritten"));
        assertTrue(prompt.contains("latest observation has `observedAt`"));
        assertTrue(prompt.contains("latest observation wins"));
        assertTrue(prompt.contains("Rewrite thoughts as the latest known state after this act"));
        assertTrue(prompt.contains("Do not preserve older missing/expected/blocker text"));
        assertTrue(prompt.contains("Where to write: put current local scope details in the most-specific thought"));
        assertTrue(prompt.contains("Promotion: before a local scope ends"));
        assertTrue(prompt.contains("carried forward into the parent when the scope exits"));
        assertFalse(prompt.contains("Gitea"));
        assertFalse(prompt.contains("Mattermost"));
        assertFalse(prompt.contains("Plane"));
        assertTrue(prompt.contains("Do not copy a thought's static purpose into content"));
    }

    @Test
    void stepSynthesisPromptFramesVerifiedAnchorAsPatchOverStaleThoughtSnapshot() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("pm-root", "project-manager-routine", "Durable PM project anchors", 300,
                "Plane project tracker missing"));
        exchange.setProperty(ThoughtStackSupport.STACK_PROPERTY, stack);
        exchange.setProperty("resolvedSynthesisRuntime", "synthesis-low");
        exchange.setProperty("thoughtUpdateDepth", 1);
        exchange.setProperty("actId", "project-management-setup");
        exchange.setProperty("actPrompt", "Create or verify the project tracker for the mission.");
        exchange.setProperty("toolPassObservedAt", java.time.Instant.parse("2026-07-07T12:00:00Z"));
        exchange.setProperty("toolPassResult", """
                Outcome: Project tracker created and verified.
                Evidence / links / ids:
                - verified: Plane project Repair Shop Queue App, id 36a6bccc-4729-4c2d-83fc-0fe48441b1bd, identifier RSQA, workspace 7a3f3b43-ef50-476b-b316-bc1bf5021393, status active/verified
                Latest observations from this act:
                - created: Plane project Repair Shop Queue App
                - verified: id=36a6bccc-4729-4c2d-83fc-0fe48441b1bd; identifier=RSQA; workspace=7a3f3b43-ef50-476b-b316-bc1bf5021393; status=active/verified
                - still missing: none
                - blockers remaining: none for project tracker
                - stale blockers resolved: Plane project tracker missing -> resolved; project RSQA verified
                Durable facts to remember: Plane project Repair Shop Queue App (RSQA) verified.
                """);

        support.prepareStepSynthesisPrompt().process(exchange);

        String prompt = exchange.getIn().getBody(LlmJobRequest.class).prompt();
        assertTrue(prompt.contains("Previous writable thought snapshots, possibly stale"));
        assertTrue(prompt.contains("Plane project tracker missing"));
        assertTrue(prompt.contains("Latest observation from this act:"));
        assertTrue(prompt.contains("36a6bccc-4729-4c2d-83fc-0fe48441b1bd"));
        assertTrue(prompt.contains("RSQA"));
        assertTrue(prompt.contains("7a3f3b43-ef50-476b-b316-bc1bf5021393"));
        assertTrue(prompt.contains("status active/verified"));
        assertTrue(prompt.contains("stale blockers resolved: Plane project tracker missing -> resolved; project RSQA verified"));
        assertTrue(prompt.contains("Previous thoughts are not authoritative; they are stale snapshots to patch."));
        assertTrue(prompt.contains("The latest observation from this act is newer than all previous thought snapshots."));
        assertTrue(prompt.contains("If the latest observation creates, verifies, activates, or confirms an anchor, remove older missing/not verified/blocker text for that same anchor."));
        assertTrue(prompt.contains("StepResult summary, artifacts, and thoughts must describe the same current state."));
        assertTrue(prompt.indexOf("Latest observation from this act:") < prompt.indexOf("Previous writable thought snapshots, possibly stale"));
        assertTrue(prompt.indexOf("Thought synthesis is a state-reduction task.") < prompt.indexOf("Thought update rules:"));
    }

    @Test
    void stepSynthesisPromptExposesRoutineAndLoopThoughtsByDefault() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("routine-thought", "Routine context", 100, "Broad state."));
        stack.push(new ThoughtFrame("loop-thought", "Loop context", 100, "Local state."));
        exchange.setProperty(ThoughtStackSupport.STACK_PROPERTY, stack);
        exchange.setProperty("resolvedSynthesisRuntime", "synthesis-low");
        exchange.setProperty("actId", "loop-closeout");
        exchange.setProperty("actPrompt", "Close out the current loop.");
        exchange.setProperty("toolPassResult", "Loop status was updated.");

        support.prepareStepSynthesisPrompt().process(exchange);

        String prompt = exchange.getIn().getBody(LlmJobRequest.class).prompt();
        String writableSection = prompt.substring(prompt.indexOf("Previous writable thought snapshots, possibly stale (only these ids may be changed):"));
        assertTrue(writableSection.contains("routine-thought"));
        assertTrue(writableSection.contains("loop-thought"));
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
        exchange.setProperty("resolvedSynthesisRuntime", "synthesis-high");
        exchange.setProperty("decisionId", "application-developer-done");
        exchange.setProperty("decisionPrompt", "Is the application developer routine complete?");

        support.prepareDecisionPrompt().process(exchange);

        LlmJobRequest request = exchange.getIn().getBody(LlmJobRequest.class);
        assertEquals("synthesis-high", request.requester());
        assertEquals("classpath:schemas/decision-result-schema.json", request.responseSchema());
        assertEquals(true, request.metadata().get(ProviderStructuredOutputSupport.FORCE_PROVIDER_SCHEMA_PROPERTY));
        assertTrue(request.systemPrompt().contains("Builds framework test scenarios"));
        assertTrue(request.systemPrompt().contains("DecisionResult JSON object"));
        assertTrue(request.prompt().contains("Test run input"));
        assertTrue(request.prompt().contains("Need repository name before coding."));
        assertTrue(request.prompt().contains("Repository is missing."));
        assertTrue(request.prompt().contains("Return only decisionId, result, confidence, and reason."));
    }


    @Test
    void createSchemaClasspathUriIsResolvedIntoPromptSchemaBody() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        exchange.setProperty("resolvedSynthesisRuntime", "synthesis-low");
        exchange.setProperty("createId", "planned-agent-runs");
        exchange.setProperty("createPrompt", "Propose the initial agent runs.");
        exchange.setProperty("createSchema", "classpath:schemas/example/software-team/agent-run-requests-schema.json");

        support.prepareCreatePrompt().process(exchange);

        LlmJobRequest request = exchange.getIn().getBody(LlmJobRequest.class);
        assertEquals("classpath:schemas/example/software-team/agent-run-requests-schema.json", request.responseSchema());
        assertEquals(true, request.metadata().get(ProviderStructuredOutputSupport.FORCE_PROVIDER_SCHEMA_PROPERTY));
        assertTrue(request.systemPrompt().contains("Builds framework test scenarios"));
        assertTrue(request.systemPrompt().contains("caller-schema-shaped JSON"));
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
        exchange.setProperty("resolvedSynthesisRuntime", "synthesis-low");
        exchange.setProperty("createId", "planned-agent-runs");
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
        assertTrue(request.prompt().contains("Routine context:"));
        assertTrue(request.prompt().contains("id: routine-a"));
        assertFalse(request.prompt().contains("routine-b"));
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
        exchange.setProperty("resolvedSynthesisRuntime", "synthesis-low");
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
        assertTrue(request.prompt().contains("source-control"));
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
        exchange.getIn().setBody(FrameworkFixtures.llmResult("synthesis-low", """
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
        exchange.getIn().setBody(FrameworkFixtures.llmResult("synthesis-low", """
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
        exchange.getIn().setBody(FrameworkFixtures.llmResult("synthesis-low", """
                {"actId":"work-item-review","summary":"decision needed","artifacts":[],"thoughts":[]}
                """));

        support.applyStepResult().process(exchange);

        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        assertEquals(AgentRun.STATUS_RUNNING, run.status());
        assertEquals("decision needed", run.latestStepResult().summary());
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
                {"actId":"release-checkpoint","summary":"done","artifacts":[],"thoughts":[{"id":"parent","content":"new parent"},{"id":"child","content":"new child"}]}
                """));

        support.applyStepResult().process(exchange);

        assertEquals("new parent", stack.frames().get(0).content());
        assertEquals("new child", stack.frames().get(1).content());
    }

    @Test
    void stepResultAllowsEmptyThoughtUpdatesForTrueNoOpPasses() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("child", "Child", 100, "old child"));
        exchange.setProperty(ThoughtStackSupport.STACK_PROPERTY, stack);
        exchange.getIn().setBody(FrameworkFixtures.llmResult("synthesis-low", """
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
        exchange.setProperty("thoughtUpdateDepth", 1);
        exchange.getIn().setBody(FrameworkFixtures.llmResult("synthesis-low", """
                {"actId":"work-item-review","summary":"done","artifacts":[],"thoughts":[{"id":"child","content":"   "}]}
                """));

        Exception failure = assertThrows(Exception.class, () -> support.applyStepResult().process(exchange));

        assertTrue(failure.getMessage().contains("blank content"));
        assertEquals("old child", stack.frames().getFirst().content());
    }

    @Test
    void stepResultRejectsNonWritableThoughtUpdate() {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("parent", "Parent", 100, "old parent"));
        stack.push(new ThoughtFrame("child", "Child", 100, "old child"));
        exchange.setProperty(ThoughtStackSupport.STACK_PROPERTY, stack);
        exchange.setProperty("thoughtUpdateDepth", 1);
        exchange.getIn().setBody(FrameworkFixtures.llmResult("synthesis-low", """
                {"actId":"work-item-review","summary":"done","artifacts":[],"thoughts":[{"id":"parent","content":"bad update"}]}
                """));

        Exception failure = assertThrows(Exception.class, () -> support.applyStepResult().process(exchange));
        assertTrue(failure.getMessage().contains("non-writable thought"));
    }

    @Test
    void decisionResultStoresFullObjectAndMirrorsBooleanForCamelControlFlow() throws Exception {
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        exchange.getIn().setBody(FrameworkFixtures.llmResult("synthesis-low", """
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
        exchange.getIn().setBody(FrameworkFixtures.llmResult("synthesis-low", "{\"wrong\":[]}"));

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
        valid.getIn().setBody(FrameworkFixtures.llmResult("synthesis-low", "{\"status\":\"ready\",\"items\":[{\"name\":\"alpha\",\"score\":3}]}"));

        support.applyCreateResult().process(valid);

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
        exchange.setProperty("createId", "planned-agent-runs");
        exchange.setProperty("schemaId", "agent-run-list");
        exchange.getIn().setBody(FrameworkFixtures.llmResult("synthesis-low", """
                {"agentRuns":[{"personaId":"routine-a","routineId":"routine-a","reason":"Implement the first vertical slice."}]}
                """));

        support.applyCreateResult().process(exchange);

        JsonNode body = assertInstanceOf(JsonNode.class, exchange.getIn().getBody());
        CreateResult result = exchange.getProperty("creates.planned-agent-runs", CreateResult.class);
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

        Exception failure = assertThrows(Exception.class, () -> support.applyCreateResult().process(exchange));

        assertTrue(failure.getMessage().contains("CreateResult schema validation failed"));
        assertTrue(failure.getMessage().toLowerCase().contains(expectedMessagePart.toLowerCase()), failure.getMessage());
    }
}
