package us.nusun.llmshare.framework.routine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import us.nusun.llmshare.framework.inventory.PersonaCatalog;
import us.nusun.llmshare.framework.inventory.RoutineCatalog;
import us.nusun.llmshare.framework.llm.ProviderStructuredOutputSupport;
import us.nusun.llmshare.framework.model.*;
import us.nusun.llmshare.framework.projection.AgentRunProjectionManager;
import us.nusun.llmshare.framework.inventory.ExecutionRuntimeRegistry;
import us.nusun.llmshare.framework.state.AgentRun;
import us.nusun.llmshare.framework.state.ThoughtStack;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class PrimitiveSupport {
    private static final int DEFAULT_THOUGHT_UPDATE_DEPTH = 2;

    @Inject ObjectMapper objectMapper;
    @Inject ThoughtStackSupport thoughts;
    @Inject PersonaCatalog personas;
    @Inject RoutineCatalog routines;
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

    public Processor prepareToolPassPrompt() {
        return exchange -> {
            AgentRun run = currentRun(exchange);
            Mission mission = exchange.getProperty("mission", Mission.class);
            Persona persona = personas.getRequired(run.personaId());
            String prompt = exchange.getProperty("actPrompt", String.class);
            String runtimeId = exchange.getProperty("resolvedToolRuntime", String.class);
            exchange.setProperty("executionRuntimeId", runtimeId);
            exchange.setProperty("personaId", persona.id());
            exchange.getIn().setBody(llmRequest(
                    exchange,
                    runtimeId,
                    persona.name(),
                    renderSystem(persona),
                    "Mission:\n" + mission.goal() + "\n\nActive thoughts:\n" + renderThoughts(exchange) + "\n\nAct prompt:\n" + prompt + "\n\nDo the requested work using available tools when useful. Return a concise factual pass result.",
                    null));
        };
    }

    public Processor captureToolPassResult() {
        return exchange -> {
            LlmJobResult result = requireSucceeded(exchange, exchange.getIn().getBody(LlmJobResult.class), "tool/action pass");
            exchange.setProperty("toolPassResult", result.content());
            projections.record(exchange, "TOOL_PASS_COMPLETED");
        };
    }

    public Processor prepareStepSynthesisPrompt() {
        return exchange -> {
            AgentRun run = currentRun(exchange);
            Persona persona = personas.getRequired(run.personaId());
            int depth = exchange.getProperty("thoughtUpdateDepth", DEFAULT_THOUGHT_UPDATE_DEPTH, Integer.class);
            String runtimeId = exchange.getProperty("resolvedSynthesisRuntime", String.class);
            exchange.setProperty("executionRuntimeId", runtimeId);
            exchange.getIn().setBody(llmRequest(
                    exchange,
                    runtimeId,
                    persona.name(),
                    "You synthesize framework action results. Return only a StepResult JSON object that matches the supplied schema. Thought content is synthesized working context, not hidden chain-of-thought.",
                    "Act id: " + exchange.getProperty("actId", String.class)
                            + "\nAct prompt: " + exchange.getProperty("actPrompt", String.class)
                            + "\nTool/action pass result:\n" + exchange.getProperty("toolPassResult", String.class)
                            + "\n\nWritable thoughts:\n" + renderWritableThoughts(exchange, depth)
                            + "\n\nReturn one universal StepResult. In `thoughts`, include only rewritten content for the writable thought ids above that should change; use [] when no thought should change. Do not copy a thought's static context into content; content should be concrete updated state.",
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

    public Processor prepareDecisionPrompt() {
        return exchange -> {
            AgentRun run = currentRun(exchange);
            Persona persona = personas.getRequired(run.personaId());
            String runtimeId = exchange.getProperty("resolvedSynthesisRuntime", String.class);
            exchange.setProperty("executionRuntimeId", runtimeId);
            exchange.getIn().setBody(llmRequest(
                    exchange,
                    runtimeId,
                    persona.name(),
                    "You answer narrow routine control-flow questions. Return only a DecisionResult JSON object.",
                    "Decision id: " + exchange.getProperty("decisionId", String.class)
                            + "\nQuestion: " + exchange.getProperty("decisionPrompt", String.class)
                            + "\nActive thoughts:\n" + renderThoughts(exchange)
                            + "\nLatest step result:\n" + asJson(run.latestStepResult())
                            + "\nReturn a boolean result with confidence, reason, and evidence.",
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

    public Processor prepareCreatePrompt() {
        return exchange -> {
            AgentRun run = currentRun(exchange);
            Persona persona = personas.getRequired(run.personaId());
            ResolvedSchema schema = resolveCreateSchema(exchange);
            exchange.setProperty("createSchemaRaw", schema.raw());
            exchange.setProperty("createSchemaJson", schema.json());
            exchange.setProperty("createSchemaUri", schema.uri());
            String runtimeId = exchange.getProperty("resolvedSynthesisRuntime", String.class);
            exchange.setProperty("executionRuntimeId", runtimeId);
            exchange.getIn().setBody(llmRequest(
                    exchange,
                    runtimeId,
                    persona.name(),
                    "You create caller-schema-shaped JSON. Return only a JSON object matching the caller schema.",
                    "Create id: " + exchange.getProperty("createId", String.class)
                            + "\nPrompt:\n" + exchange.getProperty("createPrompt", String.class)
                            + "\nMission:\n" + exchange.getProperty("mission", Mission.class).goal()
                            + "\nActive thoughts:\n" + renderThoughts(exchange)
                            + "\nCaller JSON schema:\n" + schema.json(),
                    schema.uri()));
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
            exchange.getIn().setBody(object);
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
        int depth = exchange.getProperty("thoughtUpdateDepth", DEFAULT_THOUGHT_UPDATE_DEPTH, Integer.class);
        List<String> writable = stack.writable(depth).stream().map(ThoughtFrame::id).toList();
        for (ThoughtUpdate update : updates) {
            if (!stack.contains(update.id())) throw new IllegalArgumentException("StepResult updated unknown thought: " + update.id());
            if (!writable.contains(update.id())) throw new IllegalArgumentException("StepResult updated non-writable thought: " + update.id());
        }
    }

    private String renderThoughts(Exchange exchange) {
        return renderThoughtFrames(thoughts.stack(exchange).frames());
    }

    private String renderWritableThoughts(Exchange exchange, int writableDepth) {
        return renderThoughtFrames(thoughts.stack(exchange).writable(writableDepth));
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
        out.append("  context: ").append(frame.context()).append('\n');
        out.append("  maxTokens: ").append(frame.maxTokens()).append('\n');
        out.append("  content: ").append(frame.content() == null || frame.content().isBlank() ? "(empty)" : frame.content()).append("\n");
    }

    private String renderSystem(Persona persona) {
        return "You are " + persona.name() + ". " + persona.description()
                + "\nTraits: " + String.join("; ", persona.traits())
                + "\nUse available tools when relevant. Do not claim external state unless tool-backed.";
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
