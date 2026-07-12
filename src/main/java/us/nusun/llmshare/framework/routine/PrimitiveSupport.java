package us.nusun.llmshare.framework.routine;

import static us.nusun.llmshare.framework.model.FrameworkText.firstNonBlank;
import static us.nusun.llmshare.framework.model.FrameworkText.putIfNonBlank;

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
import us.nusun.llmshare.framework.inventory.InferenceLaneRegistry;
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
    @Inject InferenceLaneRegistry lanes;
    @Inject AgentRunProjectionManager projections;

    public static final class FrameworkPrimitiveException extends RuntimeException {
        public FrameworkPrimitiveException(String message) { super(message); }
        public FrameworkPrimitiveException(String message, Throwable cause) { super(message, cause); }
    }

    public Processor projectionEvent(String eventType) {
        return exchange -> projections.record(exchange, eventType);
    }

    public Processor resolveActLane() {
        return exchange -> {
            resolveLane(exchange);
            exchange.setProperty("toolProfile", firstNonBlank(exchange.getProperty("toolProfile", String.class), "none"));
        };
    }

    public Processor resolveLane() {
        return this::resolveLane;
    }

    private void resolveLane(Exchange exchange) {
        RoutineDefinition routine = currentRoutine(exchange);
        String laneId = firstNonBlank(exchange.getProperty("lane", String.class), routine.defaultLane(), RoutineDefinition.FRAMEWORK_DEFAULT_LANE);
        lanes().getRequired(laneId);
        exchange.setProperty("resolvedLane", laneId);
    }

    public Processor markCurrent(String primitive, String idProperty) {
        return exchange -> {
            AgentRun run = currentRun(exchange);
            run.currentPrimitive(primitive);
            run.currentId(exchange.getProperty(idProperty, String.class));
            exchange.removeProperty("primitivePrompt");
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
            String laneId = exchange.getProperty("resolvedLane", String.class);
            exchange.setProperty("personaId", persona.id());
            exchange.getIn().setBody(llmRequest(
                    exchange,
                    laneId,
                    LlmPassType.TOOL,
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
            String laneId = exchange.getProperty("resolvedLane", String.class);
            exchange.getIn().setBody(llmRequest(
                    exchange,
                    laneId,
                    LlmPassType.SYNTHESIS,
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
            String laneId = exchange.getProperty("resolvedLane", String.class);
            exchange.getIn().setBody(llmRequest(
                    exchange,
                    laneId,
                    LlmPassType.SYNTHESIS,
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
            String laneId = exchange.getProperty("resolvedLane", String.class);
            exchange.getIn().setBody(llmRequest(
                    exchange,
                    laneId,
                    LlmPassType.SYNTHESIS,
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

    private LlmJobRequest llmRequest(Exchange exchange, String laneId, LlmPassType passType, String role, String systemPrompt, String prompt, String schema) {
        return new LlmJobRequest(null, laneId, passType, primitiveRequester(exchange), role, 0, prompt, systemPrompt, schema, frameworkMetadata(exchange, laneId, passType, schema), null);
    }

    private Map<String, Object> frameworkMetadata(Exchange exchange, String laneId, LlmPassType passType, String schema) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        Mission mission = exchange.getProperty("mission", Mission.class);
        if (run != null) {
            putIfNonBlank(metadata, "agentRunId", run.id());
            putIfNonBlank(metadata, "personaId", run.personaId());
            putIfNonBlank(metadata, "primitive", run.currentPrimitive());
            putIfNonBlank(metadata, "primitiveId", run.currentId());
        }
        if (mission != null) {
            putIfNonBlank(metadata, "missionId", mission.id());
        }
        putIfNonBlank(metadata, "actId", exchange.getProperty("actId", String.class));
        putIfNonBlank(metadata, "decisionId", exchange.getProperty("decisionId", String.class));
        putIfNonBlank(metadata, "createId", exchange.getProperty("createId", String.class));
        putIfNonBlank(metadata, "toolProfile", exchange.getProperty("toolProfile", String.class));
        putIfNonBlank(metadata, "laneId", laneId);
        putIfNonBlank(metadata, "passType", passType == null ? null : passType.name());
        if (schema != null && !schema.isBlank()) {
            metadata.put(ProviderStructuredOutputSupport.FORCE_PROVIDER_SCHEMA_PROPERTY, true);
        }
        return Map.copyOf(metadata);
    }


    private static String primitiveRequester(Exchange exchange) {
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        String primitive = run == null ? exchange.getProperty("currentPrimitive", String.class) : run.currentPrimitive();
        String id = run == null ? exchange.getProperty("currentId", String.class) : run.currentId();
        return firstNonBlank(primitive == null ? null : primitive + (id == null || id.isBlank() ? "" : ":" + id), "framework");
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
                    - You have access to only one tool profile during this pass.

                    Bounded execution:
                    - Work only on the highlighted ACT PROMPT.
                    - Use the fewest useful tool calls; target 1-3 calls for normal steps.
                    - Stop when the requested evidence, mutation, or blocker is known.
                    - Do not browse entire projects, repositories, documentation trees, or channels.
                    - Do not re-check mission context unless this act prompt requires it.

                    Contract:
                    - Do tool-backed work using available tools.
                    - Never do anything except the specific act.
                    - Do not claim external state unless tool-backed.
                    - Return only a compact plain-text pass report.
                    """;
            case "synthesis" -> """

                    Contract:
                    - Synthesize the completed action result into the required JSON.
                    - Return only valid JSON object matching the supplied schema.
                    - This pass has no tools; do not call tools, reason about tool availability, or retry the action.
                    - The preceding action has already completed; do not repeat it.
                    - Treat the supplied latest observation as the newest evidence.
                    - Rewrite thoughts as current-state snapshots.
                    - Thought content is a compact running notebook, not hidden chain-of-thought.
                    """;
            case "decision" -> """

                    Contract:
                    - Answer one narrow routine control-flow question.
                    - Use only supplied context; this pass has no tools.
                    - Return only a DecisionResult JSON object.
                    """;
            case "create" -> """

                    Contract:
                    - Create caller-schema-shaped JSON for the highlighted prompt only.
                    - Use only supplied context; this pass has no tools.
                    - Return only a JSON object matching the caller schema.
                    """;
            default -> throw new IllegalArgumentException("Unknown primitive system contract: " + primitive);
        };
    }

    private String renderPersonaBehavior(Persona persona) {
        return """
                You are %s. %s
                Traits: %s
                """.formatted(
                safeText(persona.name()),
                safeText(persona.description()),
                persona.traits().isEmpty() ? "(none)" : String.join("; ", persona.traits())).strip();
    }

    private String renderToolPassPrompt(Exchange exchange) {
        return """
                %s

                ## Step-Level Context
                - Primitive: act
                - Act id: %s
                - Tool profile: %s

                ### Active thoughts:
                %s

                ### Tool-profile guidance:
                %s

                ## Rules / Execution Guidance
                - The highlighted ACT PROMPT is the only work that matters right now.
                - Take the smallest useful path: one direct read or mutation first, then stop when enough is known.
                - Normal steps should finish in 1-3 tool calls. If more is needed, report the remaining narrow follow-up.
                - Prefer direct ids, links, and named anchors from context over broad searches.
                - Do not scan whole systems or refresh unrelated mission state.
                - Label verified evidence and assumptions plainly.

                ## ACT PROMPT - DO THIS NOW
                This is the only goal for this pass. Use prior context only when it helps complete this act.

                %s

                ## Response Format
                Return a compact pass report in this shape:
                Outcome:
                Tool calls used:
                Actions taken:
                Evidence / links / ids:
                - Label links/ids as verified, missing, expected, or reference. Do not include guessed URLs.
                Latest observations from this act:
                - created:
                - verified:
                Durable facts to remember:
                - Preserve only verified current anchors
                State changed:
                """.formatted(
                missionLevelContext(exchange),
                safeText(exchange.getProperty("actId", String.class)),
                safeText(exchange.getProperty("toolProfileId", String.class)),
                exchange.getProperty("activeThoughts", String.class),
                exchange.getProperty("toolProfileGuidance", String.class),
                safeText(exchange.getProperty("actPrompt", String.class)));
    }

    private String renderStepSynthesisPrompt(Exchange exchange) {
        return """
                %s

                ## Step-Level Context
                - Primitive: synthesis
                - Act id: %s

                ### Preceding action objective, for context only:
                %s
                Do not execute or repeat this objective during synthesis.

                Latest observation observedAt: %s

                ### Latest observation from this act:
                %s

                ### Previous thought snapshots, possibly stale:
                %s

                ## Rules / Execution Guidance
                - No tools are available in this pass.
                - Synthesize only the completed act and the latest observation.
                - Keep the result compact and current; do not expand into broad planning.

                ## SYNTHESIS PROMPT - DO THIS NOW
                Produce one StepResult JSON object for this act. This is the only task for this pass.

                ## Response Format
                Return one StepResult JSON object.

                Step boundary:
                - Use only the latest observation and supplied context.
                - Do not do new work, ask for tools, or broaden the scope.
                - Keep summaries and thought rewrites compact.

                Thought synthesis:
                - Treat previous thoughts as stale snapshots and the latest observation as the newest state.
                - When the latest observation conflicts with previous thoughts about the same anchor, the latest observation wins.
                - Make the summary, artifacts, and thoughts describe the same current state.
                - Replace stale missing or blocker details for anchors changed by this act; preserve unrelated durable facts.

                Thought updates:
                - In `thoughts`, include every thought id whose durable memory changed; use [] only when no durable context changed.
                - Only use thought ids listed in the previous thought snapshots.
                - Rewrite each included thought as complete, compact, current memory. Content must be non-empty and within maxTokens.
                - Preserve mission facts, decisions, artifact ids or URLs, owners.
                - Drop stale, duplicate, speculative, low-signal, or overly detailed notes.

                Artifact accuracy:
                - Use URLs only when verified by tool output, durable documentation, or prior verified thoughts.
                - Represent missing artifacts as missing or blocked without invented URLs.
                - Do not promote reference artifacts from other projects as current mission anchors.
                """.formatted(
                missionLevelContext(exchange),
                safeText(exchange.getProperty("actId", String.class)),
                exchange.getProperty("precedingActionObjective", String.class),
                safeText(exchangePropertyText(exchange, "toolPassObservedAt")),
                safeText(exchange.getProperty("toolPassResult", String.class)),
                exchange.getProperty("activeThoughts", String.class));
    }

    private String renderDecisionPrompt(Exchange exchange) {
        return """
                %s

                ## Step-Level Context
                - Primitive: decide
                - Decision id: %s

                ### Active thoughts:
                %s

                ### Latest step result:
                %s

                ## Rules / Execution Guidance
                - Answer only the highlighted decision question.
                - Do not invent missing evidence or perform work outside this decision.
                - Prefer false when the supplied evidence is incomplete, stale, or blocked.

                ## DECISION PROMPT - ANSWER THIS NOW
                Question: %s

                ## Response Format
                Return only decisionId, result, confidence, and reason.
                """.formatted(
                missionLevelContext(exchange),
                safeText(exchange.getProperty("decisionId", String.class)),
                exchange.getProperty("activeThoughts", String.class),
                exchange.getProperty("latestStepResultJson", String.class),
                safeText(exchange.getProperty("decisionPrompt", String.class)));
    }

    private String renderCreatePrompt(Exchange exchange) {
        return """
                %s

                ## Step-Level Context
                - Primitive: create
                - Create id: %s

                ### Active thoughts:
                %s

                ## Rules / Execution Guidance
                - Produce only the JSON object requested by the caller schema.
                - Use catalog data only when the create prompt explicitly includes it.
                - Do not add explanatory prose or fields outside the schema.

                ## CREATE PROMPT - PRODUCE THIS NOW
                %s

                ## Caller JSON schema:
                %s
                """.formatted(
                missionLevelContext(exchange),
                safeText(exchange.getProperty("createId", String.class)),
                exchange.getProperty("activeThoughts", String.class),
                safeText(exchange.getProperty("createPrompt", String.class)),
                exchange.getProperty("createSchemaJson", String.class));
    }

    private String missionLevelContext(Exchange exchange) {
        return """
                ## Mission-Level Context
                ### Mission:
                %s

                ### Agent-run input:
                %s

                ### Routine context:
                %s

                ### Mission roster:
                %s""".formatted(
                exchange.getProperty("missionGoal", String.class),
                exchange.getProperty("agentRunInput", String.class),
                exchange.getProperty("routineSummary", String.class),
                exchange.getProperty("missionRoster", String.class));
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

    private InferenceLaneRegistry lanes() {
        return lanes == null ? new InferenceLaneRegistry(List.of()) : lanes;
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
    private record ResolvedSchema(String raw, String json, String uri) {}
}
