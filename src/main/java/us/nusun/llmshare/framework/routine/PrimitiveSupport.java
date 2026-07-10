package us.nusun.llmshare.framework.routine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import us.nusun.llmshare.framework.inventory.PersonaCatalog;
import us.nusun.llmshare.framework.inventory.RoutineCatalog;
import us.nusun.llmshare.framework.inventory.ToolProfileRegistry;
import us.nusun.llmshare.framework.llm.ProviderStructuredOutputSupport;
import us.nusun.llmshare.framework.service.MissionRosterService;
import us.nusun.llmshare.framework.model.*;
import us.nusun.llmshare.framework.projection.AgentRunProjectionManager;
import us.nusun.llmshare.framework.inventory.ExecutionRuntimeRegistry;
import us.nusun.llmshare.framework.state.AgentRun;
import us.nusun.llmshare.framework.state.AgentRunStore;
import us.nusun.llmshare.framework.state.ThoughtStack;

import java.io.InputStream;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
@Named("primitiveSupport")
public class PrimitiveSupport {
    @Inject ObjectMapper objectMapper;
    @Inject ThoughtStackSupport thoughts;
    @Inject PersonaCatalog personas;
    @Inject RoutineCatalog routines;
    @Inject ToolProfileRegistry toolProfiles;
    @Inject MissionRosterService missionRoster;
    @Inject ExecutionRuntimeRegistry runtimes;
    @Inject AgentRunProjectionManager projections;

    public static final class FrameworkPrimitiveException extends RuntimeException {
        public FrameworkPrimitiveException(String message) { super(message); }
        public FrameworkPrimitiveException(String message, Throwable cause) { super(message, cause); }
    }

    public Processor projectionEvent(String eventType) {
        return exchange -> projections.record(exchange, eventType);
    }

    public Processor resolveActRuntimes() {
        return exchange -> {
            RoutineDefinition routine = currentRoutine(exchange);
            String toolRuntime = firstNonBlank(exchange.getProperty("toolRuntime", String.class), routine.defaultToolRuntime(), "tool-low");
            String synthesisRuntime = firstNonBlank(exchange.getProperty("synthesisRuntime", String.class), routine.defaultSynthesisRuntime(), "synthesis-low");
            exchange.setProperty("resolvedToolRuntime", toolRuntime);
            exchange.setProperty("resolvedSynthesisRuntime", synthesisRuntime);
            exchange.setProperty("toolProfile", firstNonBlank(exchange.getProperty("toolProfile", String.class), "none"));
        };
    }

    public Processor resolveSynthesisRuntime(String overrideProperty) {
        return exchange -> {
            RoutineDefinition routine = currentRoutine(exchange);
            String synthesisRuntime = firstNonBlank(
                    exchange.getProperty(overrideProperty, String.class),
                    routine.defaultSynthesisRuntime(),
                    "synthesis-low");
            exchange.setProperty("resolvedSynthesisRuntime", synthesisRuntime);
        };
    }

    public Processor markCurrent(String primitive, String idProperty) {
        return exchange -> {
            AgentRun run = currentRun(exchange);
            run.currentPrimitive(primitive);
            run.currentId(exchange.getProperty(idProperty, String.class));
            exchange.setProperty("personaId", run.personaId());
            projections.record(exchange, primitive.toUpperCase() + "_STARTED");
        };
    }

    public Processor prepareToolPassPromptContext() {
        return this::prepareToolPassContext;
    }

    public Processor prepareToolPassPrompt() {
        return exchange -> {
            if (!hasPrimitivePrompt(exchange)) {
                prepareToolPassContext(exchange);
            }
            AgentRun run = currentRun(exchange);
            Persona persona = personas.getRequired(run.personaId());
            String runtimeId = exchange.getProperty("resolvedToolRuntime", String.class);
            exchange.setProperty("executionRuntimeId", runtimeId);
            exchange.setProperty("personaId", persona.id());
            exchange.getIn().setBody(llmRequest(
                    exchange,
                    runtimeId,
                    persona.name(),
                    primitiveSystemPrompt(exchange, "tool"),
                    primitivePrompt(exchange, this::renderToolPassPrompt),
                    null));
        };
    }

    public Processor captureToolPassResult() {
        return exchange -> {
            LlmJobResult result = requireSucceeded(exchange, exchange.getIn().getBody(LlmJobResult.class), "tool/action pass");
            exchange.setProperty("toolPassObservedAt", Instant.now());
            exchange.setProperty("toolPassResult", result.content());
            projections.record(exchange, "TOOL_PASS_COMPLETED");
        };
    }

    public Processor prepareStepSynthesisPromptContext() {
        return this::prepareStepSynthesisContext;
    }

    public Processor prepareStepSynthesisPrompt() {
        return exchange -> {
            if (!hasPrimitivePrompt(exchange)) {
                prepareStepSynthesisContext(exchange);
            }
            AgentRun run = currentRun(exchange);
            Persona persona = personas.getRequired(run.personaId());
            String runtimeId = exchange.getProperty("resolvedSynthesisRuntime", String.class);
            exchange.setProperty("executionRuntimeId", runtimeId);
            exchange.getIn().setBody(llmRequest(
                    exchange,
                    runtimeId,
                    persona.name(),
                    primitiveSystemPrompt(exchange, "synthesis"),
                    primitivePrompt(exchange, this::renderStepSynthesisPrompt),
                    "classpath:schemas/step-result-schema.json"));
        };
    }

    public Processor applyStepResult() {
        return exchange -> {
            LlmJobResult llm = requireSucceeded(exchange, exchange.getIn().getBody(LlmJobResult.class), "step synthesis pass");
            StepResult result = parse(llm.content(), StepResult.class);
            validateThoughtUpdates(exchange, result.thoughts());
            ThoughtStack stack = thoughts.stack(exchange);
            for (ThoughtUpdate update : result.thoughts()) {
                stack.rewrite(update.id(), update.content());
            }
            AgentRun run = currentRun(exchange);
            run.apply(result);
            exchange.setProperty("latestStepResult", result);
            exchange.getIn().setBody(result);
            projections.record(exchange, "STEP_RESULT_APPLIED");
            projections.record(exchange, "ACT_COMPLETED");
        };
    }

    public Processor prepareDecisionPromptContext() {
        return this::prepareDecisionContext;
    }

    public Processor prepareDecisionPrompt() {
        return exchange -> {
            if (!hasPrimitivePrompt(exchange)) {
                prepareDecisionContext(exchange);
            }
            AgentRun run = currentRun(exchange);
            Persona persona = personas.getRequired(run.personaId());
            String runtimeId = exchange.getProperty("resolvedSynthesisRuntime", String.class);
            exchange.setProperty("executionRuntimeId", runtimeId);
            exchange.getIn().setBody(llmRequest(
                    exchange,
                    runtimeId,
                    persona.name(),
                    primitiveSystemPrompt(exchange, "decision"),
                    primitivePrompt(exchange, this::renderDecisionPrompt),
                    "classpath:schemas/decision-result-schema.json"));
        };
    }

    public Processor applyDecisionResult() {
        return exchange -> {
            LlmJobResult llm = requireSucceeded(exchange, exchange.getIn().getBody(LlmJobResult.class), "decision synthesis pass");
            DecisionResult result = parse(llm.content(), DecisionResult.class);
            currentRun(exchange).apply(result);
            exchange.setProperty("decisions." + result.decisionId(), result);
            exchange.setProperty(
                    "decision." + result.decisionId() + ".result",
                    result.result());
            exchange.getIn().setBody(result);
            projections.record(exchange, "DECISION_COMPLETED");
        };
    }

    public Processor prepareCreatePromptContext() {
        return this::prepareCreateContext;
    }

    public Processor prepareCreatePrompt() {
        return exchange -> {
            if (!hasPrimitivePrompt(exchange) || exchange.getProperty("createSchemaJson", String.class) == null) {
                prepareCreateContext(exchange);
            }
            AgentRun run = currentRun(exchange);
            Persona persona = personas.getRequired(run.personaId());
            String runtimeId = exchange.getProperty("resolvedSynthesisRuntime", String.class);
            exchange.setProperty("executionRuntimeId", runtimeId);
            exchange.getIn().setBody(llmRequest(
                    exchange,
                    runtimeId,
                    persona.name(),
                    primitiveSystemPrompt(exchange, "create"),
                    primitivePrompt(exchange, this::renderCreatePrompt),
                    exchange.getProperty("createSchemaUri", String.class)));
        };
    }

    public Processor applyCreateResult() {
        return exchange -> {
            LlmJobResult llm = requireSucceeded(exchange, exchange.getIn().getBody(LlmJobResult.class), "create synthesis pass");
            JsonNode object = objectMapper.readTree(llm.content());
            validateAgainstSchema(exchange.getProperty("createSchemaJson", String.class), object);
            CreateResult result = new CreateResult(
                    exchange.getProperty("createId", String.class),
                    exchange.getProperty("schemaId", "caller-schema", String.class),
                    object);
            currentRun(exchange).apply(result);
            exchange.setProperty("creates." + result.createId(), result);
            exchange.setProperty("latestCreateResult", result);
            exchange.getIn().setBody(object.toString());
            projections.record(exchange, "CREATE_COMPLETED");
        };
    }

    private LlmJobRequest llmRequest(Exchange exchange, String runtimeId, String role, String systemPrompt, String prompt, String schema) {
        return new LlmJobRequest(null, runtimeId, role, 0, prompt, systemPrompt, schema, frameworkMetadata(exchange, runtimeId, schema), null);
    }

    private Map<String, Object> frameworkMetadata(Exchange exchange, String runtimeId, String schema) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        Mission mission = exchange.getProperty("mission", Mission.class);
        if (run != null) {
            putIfPresent(metadata, "agentRunId", run.id());
            putIfPresent(metadata, "personaId", run.personaId());
            putIfPresent(metadata, "primitive", run.currentPrimitive());
            putIfPresent(metadata, "primitiveId", run.currentId());
        }
        if (mission != null) {
            putIfPresent(metadata, "missionId", mission.id());
        }
        putIfPresent(metadata, "actId", exchange.getProperty("actId", String.class));
        putIfPresent(metadata, "decisionId", exchange.getProperty("decisionId", String.class));
        putIfPresent(metadata, "createId", exchange.getProperty("createId", String.class));
        putIfPresent(metadata, "toolProfile", exchange.getProperty("toolProfile", String.class));
        putIfPresent(metadata, "executionRuntimeId", runtimeId);
        if (runtimeId != null && !runtimeId.isBlank()) {
            putIfPresent(metadata, "inferenceLane", runtimes.getRequired(runtimeId).laneId());
        }
        if (schema != null && !schema.isBlank()) {
            metadata.put(ProviderStructuredOutputSupport.FORCE_PROVIDER_SCHEMA_PROPERTY, true);
        }
        return Map.copyOf(metadata);
    }

    private static void putIfPresent(Map<String, Object> metadata, String key, String value) {
        if (value != null && !value.isBlank()) metadata.put(key, value);
    }

    private LlmJobResult requireSucceeded(Exchange exchange, LlmJobResult llm, String pass) {
        if (llm == null) {
            projections.record(exchange, "LLM_PASS_FAILED");
            throw new FrameworkPrimitiveException("LLM " + pass + " did not return a result");
        }
        if (llm.status() != LlmJobStatus.SUCCEEDED) {
            exchange.setProperty("latestPrimitiveFailure", firstNonBlank(llm.error(), "unknown error"));
            projections.record(exchange, "LLM_PASS_FAILED");
            throw new FrameworkPrimitiveException("LLM " + pass + " failed: " + firstNonBlank(llm.error(), "unknown error"));
        }
        if (llm.content() == null || llm.content().isBlank()) {
            exchange.setProperty("latestPrimitiveFailure", "empty LLM content");
            projections.record(exchange, "LLM_PASS_FAILED");
            throw new FrameworkPrimitiveException("LLM " + pass + " returned empty content");
        }
        return llm;
    }

    private void validateThoughtUpdates(Exchange exchange, List<ThoughtUpdate> updates) {
        ThoughtStack stack = thoughts.stack(exchange);
        for (ThoughtUpdate update : updates) {
            if (!stack.contains(update.id())) throw new IllegalArgumentException("StepResult updated unknown thought: " + update.id());
            if (update.content() == null || update.content().isBlank()) {
                throw new IllegalArgumentException("StepResult updated thought with blank content: " + update.id());
            }
        }
    }

    private String renderThoughts(Exchange exchange) {
        return renderThoughtFrames(thoughts.stack(exchange).frames());
    }

    private static String renderThoughtFrames(List<ThoughtFrame> frames) {
        if (frames.isEmpty()) {
            return "(none)";
        }
        StringBuilder out = new StringBuilder();
        for (ThoughtFrame frame : frames) {
            appendThought(out, frame);
        }
        return out.toString();
    }

    private static void appendThought(StringBuilder out, ThoughtFrame frame) {
        out.append("- id: ").append(frame.id()).append('\n');
        out.append("  name: ").append(frame.name()).append('\n');
        out.append("  purpose: ").append(frame.context()).append('\n');
        out.append("  maxTokens: ").append(frame.maxTokens()).append('\n');
        out.append("  updatedAt: ").append(frame.updatedAt()).append('\n');
        out.append("  content: ").append(frame.content() == null || frame.content().isBlank() ? "(empty)" : frame.content()).append("\n");
    }

    private void prepareCommonPromptContext(Exchange exchange) {
        AgentRun run = currentRun(exchange);
        Mission mission = exchange.getProperty("mission", Mission.class);
        RoutineDefinition routine = currentRoutine(exchange);
        exchange.setProperty("missionGoal", mission == null ? "(none)" : safeText(mission.goal()));
        exchange.setProperty("agentRunInput", renderAgentRunInput(run));
        exchange.setProperty("routineSummary", renderRoutineSummary(routine));
        exchange.setProperty("missionRoster", missionRoster().missionRosterPrompt(exchange));
        exchange.setProperty("activeThoughts", renderThoughts(exchange));
    }

    private void prepareToolPassContext(Exchange exchange) {
        prepareCommonPromptContext(exchange);
        String profileId = firstNonBlank(exchange.getProperty("toolProfile", String.class), ToolProfileRegistry.NONE_ID);
        ToolProfile profile = toolProfiles().getRequired(profileId);
        exchange.setProperty("toolProfileId", profile.id());
        exchange.setProperty("toolProfileGuidance", profile.promptGuidance().isBlank() ? "(none)" : profile.promptGuidance());
    }

    private void prepareStepSynthesisContext(Exchange exchange) {
        prepareCommonPromptContext(exchange);
        exchange.setProperty("precedingActionObjective", compactObjective(exchange.getProperty("actPrompt", String.class)));
    }

    private void prepareDecisionContext(Exchange exchange) {
        prepareCommonPromptContext(exchange);
        exchange.setProperty("latestStepResultJson", asJson(currentRun(exchange).latestStepResult()));
    }

    private void prepareCreateContext(Exchange exchange) throws Exception {
        prepareCommonPromptContext(exchange);
        ResolvedSchema schema = resolveCreateSchema(exchange);
        exchange.setProperty("createSchemaRaw", schema.raw());
        exchange.setProperty("createSchemaJson", schema.json());
        exchange.setProperty("createSchemaUri", schema.uri());
    }

    private String primitivePrompt(Exchange exchange, PromptFallback fallback) {
        String prompt = exchange.getProperty("primitivePrompt", String.class);
        return prompt == null || prompt.isBlank() ? fallback.render(exchange) : prompt;
    }

    private boolean hasPrimitivePrompt(Exchange exchange) {
        String prompt = exchange.getProperty("primitivePrompt", String.class);
        return prompt != null && !prompt.isBlank();
    }

    private String primitiveSystemPrompt(Exchange exchange, String primitive) {
        Persona persona = personas.getRequired(currentRun(exchange).personaId());
        return renderPersonaBehavior(persona)
                + switch (primitive) {
            case "tool" -> """

                    Tool access:
                    - You have access to only 1 tool profile at a time
                    - If you lack a particular tool for this step, that's not a blocker, you will have access in later steps

                    Contract:
                    - Do tool-backed work using available tools
                    - Never do anything except for the specific act
                    - Do not claim external state unless tool-backed.
                    - Return only a compact plain-text pass report.
                    """;
            case "synthesis" -> """

                    Contract:
                    - Synthesize framework action results.
                    - Return only valid JSON object matching the supplied schema.
                    - This pass has no tools; do not call tools, reason about tool availability, or retry the action.
                    - The preceding action has already completed; do not repeat it.
                    - Treat the supplied latest observation as the newest evidence.
                    - Rewrite thoughts as current-state snapshots.
                    - Thought content is a compact running notebook, not hidden chain-of-thought.
                    """;
            case "decision" -> """

                    Contract: Answer a narrow routine control-flow question. Return only a DecisionResult JSON object.
                    """;
            case "create" -> """

                    Contract: Create caller-schema-shaped JSON. Return only a JSON object matching the caller schema.
                    """;
            default -> throw new IllegalArgumentException("Unknown primitive system contract: " + primitive);
        };
    }

    private String renderPersonaBehavior(Persona persona) {
        return "You are " + safeText(persona.name()) + ". " + safeText(persona.description())
                + "\nTraits: " + (persona.traits().isEmpty() ? "(none)" : String.join("; ", persona.traits()));
    }

    private String renderToolPassPrompt(Exchange exchange) {
        return "Mission:\n" + exchange.getProperty("missionGoal", String.class)
                + "\n\nAgent-run input:\n" + exchange.getProperty("agentRunInput", String.class)
                + "\n\nRoutine context:\n" + exchange.getProperty("routineSummary", String.class)
                + "\n\nMission roster:\n" + exchange.getProperty("missionRoster", String.class)
                + "\n\nAct id: " + safeText(exchange.getProperty("actId", String.class))
                + "\nTool profile: " + exchange.getProperty("toolProfileId", String.class)
                + "\nTool-profile guidance:\n" + exchange.getProperty("toolProfileGuidance", String.class)
                + "\n\nActive thoughts:\n" + exchange.getProperty("activeThoughts", String.class)
                + "\n\nAct prompt:\n" + safeText(exchange.getProperty("actPrompt", String.class))
                + "\n\nDo the requested work using available tools when useful. Return a compact pass report in this shape:"
                + passReportShape();
    }

    private String renderStepSynthesisPrompt(Exchange exchange) {
        return "Mission:\n" + exchange.getProperty("missionGoal", String.class)
                + "\n\nAgent-run input:\n" + exchange.getProperty("agentRunInput", String.class)
                + "\n\nRoutine context:\n" + exchange.getProperty("routineSummary", String.class)
                + "\n\nMission roster:\n" + exchange.getProperty("missionRoster", String.class)
                + "\n\nAct id: " + safeText(exchange.getProperty("actId", String.class))
                + "\nPreceding action objective, for context only:\n" + exchange.getProperty("precedingActionObjective", String.class)
                + "\nDo not execute or repeat this objective during synthesis."
                + "\nLatest observation observedAt: " + safeText(exchangePropertyText(exchange, "toolPassObservedAt"))
                + "\nLatest observation from this act:\n" + safeText(exchange.getProperty("toolPassResult", String.class))
                + "\n\nPrevious thought snapshots, possibly stale:\n" + exchange.getProperty("activeThoughts", String.class)
                + stepResultRules();
    }

    private String renderDecisionPrompt(Exchange exchange) {
        return "Mission:\n" + exchange.getProperty("missionGoal", String.class)
                + "\n\nAgent-run input:\n" + exchange.getProperty("agentRunInput", String.class)
                + "\n\nRoutine context:\n" + exchange.getProperty("routineSummary", String.class)
                + "\n\nDecision id: " + safeText(exchange.getProperty("decisionId", String.class))
                + "\nQuestion: " + safeText(exchange.getProperty("decisionPrompt", String.class))
                + "\nActive thoughts:\n" + exchange.getProperty("activeThoughts", String.class)
                + "\nLatest step result:\n" + exchange.getProperty("latestStepResultJson", String.class)
                + "\nReturn only decisionId, result, confidence, and reason.";
    }

    private String renderCreatePrompt(Exchange exchange) {
        return "Create id: " + safeText(exchange.getProperty("createId", String.class))
                + "\nPrompt:\n" + safeText(exchange.getProperty("createPrompt", String.class))
                + "\nMission:\n" + exchange.getProperty("missionGoal", String.class)
                + "\nAgent-run input:\n" + exchange.getProperty("agentRunInput", String.class)
                + "\nRoutine context:\n" + exchange.getProperty("routineSummary", String.class)
                + "\nMission roster:\n" + exchange.getProperty("missionRoster", String.class)
                + "\nActive thoughts:\n" + exchange.getProperty("activeThoughts", String.class)
                + "\nCaller JSON schema:\n" + exchange.getProperty("createSchemaJson", String.class);
    }

    public String passReportShape() {
        return "\nOutcome:"
                + "\nActions taken:"
                + "\nEvidence / links / ids:"
                + "\n- Label links/ids as verified, missing, expected, or reference. Do not include guessed URLs."
                + "\nLatest observations from this act:"
                + "\n- created:"
                + "\n- verified:"
                + "\n- still missing:"
                + "\n- blockers remaining:"
                + "\n- stale blockers resolved:"
                + "\nDurable facts to remember:"
                + "\n- Preserve verified current anchors; record missing/expected artifacts as blockers without fake URLs; label reference examples as reference/patterns."
                + "\nState changed:"
                + "\nBlockers:";
    }

    public String stepResultRules() {
        return """

                Return one StepResult JSON object.

                Thought synthesis:
                - Treat previous thoughts as stale snapshots and the latest observation as the newest state.
                - When the latest observation conflicts with previous thoughts about the same anchor, the latest observation wins.
                - Make the summary, artifacts, and thoughts describe the same current state.
                - Replace stale missing or blocker details for anchors changed by this act; preserve unrelated durable facts.

                Thought updates:
                - In `thoughts`, include every thought id whose durable memory changed; use [] only when no durable context changed.
                - Only use thought ids listed in the previous thought snapshots.
                - Rewrite each included thought as complete, compact, current memory. Content must be non-empty and within maxTokens.
                - Preserve mission facts, decisions, artifact ids or URLs, owners, blockers, and unblock conditions that still matter.
                - Drop stale, duplicate, speculative, low-signal, or overly detailed notes.

                Artifact accuracy:
                - Use URLs only when verified by tool output, durable documentation, or prior verified thoughts.
                - Represent missing artifacts as missing or blocked without invented URLs.
                - Do not promote reference artifacts from other projects as current mission anchors.
                """;
    }

    private static String exchangePropertyText(Exchange exchange, String key) {
        Object value = exchange.getProperty(key);
        return value == null ? "" : String.valueOf(value);
    }

    private String renderAgentRunInput(AgentRun run) {
        Object input = run == null ? null : run.workingState().get("input");
        if (isEmptyInput(input)) {
            return "(empty)";
        }
        return asJson(input);
    }

    private static boolean isEmptyInput(Object input) {
        if (input == null) return true;
        if (input instanceof String text) return text.isBlank();
        if (input instanceof Map<?, ?> map) return map.isEmpty();
        if (input instanceof Iterable<?> iterable) return !iterable.iterator().hasNext();
        return false;
    }

    private static String renderRoutineSummary(RoutineDefinition routine) {
        StringBuilder out = new StringBuilder();
        out.append("id: ").append(safeText(routine.id()));
        if (routine.name() != null && !routine.name().isBlank()) {
            out.append("\nname: ").append(safeText(routine.name()));
        }
        String description = compact(routine.description());
        if (description != null && description.length() <= 240) {
            out.append("\ndescription: ").append(description);
        }
        return out.toString();
    }

    private ToolProfileRegistry toolProfiles() {
        return toolProfiles == null ? new ToolProfileRegistry(List.of()) : toolProfiles;
    }

    private MissionRosterService missionRoster() {
        return missionRoster == null
                ? new MissionRosterService(new AgentRunStore(), personas, routines)
                : missionRoster;
    }

    private static String safeText(String value) {
        return value == null || value.isBlank() ? "(none)" : value;
    }

    private static String compact(String value) {
        if (value == null || value.isBlank()) return null;
        return value.strip().replaceAll("\\s+", " ");
    }

    private static String compactObjective(String value) {
        String text = compact(value);
        if (text == null) return "(none)";
        int maxChars = 240;
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars - 3).strip() + "...";
    }

    @FunctionalInterface
    private interface PromptFallback {
        String render(Exchange exchange);
    }

    private ResolvedSchema resolveCreateSchema(Exchange exchange) throws Exception {
        String explicitJson = exchange.getProperty("createSchemaJson", String.class);
        String explicitUri = exchange.getProperty("createSchemaUri", String.class);
        String raw = exchange.getProperty("createSchema", String.class);
        if (explicitJson != null && !explicitJson.isBlank()) {
            return new ResolvedSchema(firstNonBlank(raw, explicitJson), explicitJson, explicitUri);
        }
        String candidate = firstNonBlank(raw, explicitUri);
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalArgumentException("createSchema or createSchemaJson is required");
        }
        if (isSchemaUri(candidate)) {
            return new ResolvedSchema(candidate, loadSchema(candidate), candidate);
        }
        return new ResolvedSchema(candidate, candidate, explicitUri);
    }

    private boolean isSchemaUri(String value) {
        return value.startsWith("classpath:") || value.startsWith("file:");
    }

    private String loadSchema(String uri) throws Exception {
        if (uri.startsWith("classpath:")) {
            String path = uri.substring("classpath:".length());
            if (path.startsWith("/")) path = path.substring(1);
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            try (InputStream in = loader.getResourceAsStream(path)) {
                if (in == null) throw new IllegalArgumentException("Schema resource not found: " + uri);
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        if (uri.startsWith("file:")) {
            return java.nio.file.Files.readString(java.nio.file.Path.of(java.net.URI.create(uri)));
        }
        throw new IllegalArgumentException("Unsupported schema URI: " + uri);
    }

    private void validateAgainstSchema(String schemaJson, JsonNode object) throws Exception {
        if (schemaJson == null || schemaJson.isBlank()) return;
        JsonNode schema = objectMapper.readTree(schemaJson);
        List<Error> errors = SchemaRegistry
                .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(schema)
                .validate(object);
        if (!errors.isEmpty()) {
            String message = errors.stream().map(Error::getMessage).sorted().collect(Collectors.joining("; "));
            throw new FrameworkPrimitiveException("CreateResult schema validation failed: " + message);
        }
    }

    private AgentRun currentRun(Exchange exchange) {
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        if (run == null) throw new IllegalStateException("No AgentRun on exchange");
        return run;
    }

    private RoutineDefinition currentRoutine(Exchange exchange) {
        return routines.getRequired(currentRun(exchange).routineId());
    }

    private <T> T parse(String content, Class<T> type) throws Exception {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("LLM returned empty content for " + type.getSimpleName());
        return objectMapper.readValue(content, type);
    }

    private String asJson(Object value) {
        try { return value == null ? "null" : objectMapper.writeValueAsString(value); }
        catch (Exception e) { return String.valueOf(value); }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private record ResolvedSchema(String raw, String json, String uri) {}
}
