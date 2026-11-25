package us.nusun.tack.framework.routine;

import static us.nusun.tack.framework.FrameworkText.firstNonBlank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import us.nusun.tack.framework.persona.PersonaCatalog;
import us.nusun.tack.framework.tool.ToolProfileRegistry;
import us.nusun.tack.framework.inference.SchemaContract;
import us.nusun.tack.framework.inference.LlmExecutionContext;
import us.nusun.tack.framework.inference.LlmJobEnvelope;
import us.nusun.tack.framework.inference.LlmJobResult;
import us.nusun.tack.framework.inference.LlmJobStatus;
import us.nusun.tack.framework.inference.LlmPassType;
import us.nusun.tack.framework.tool.ToolProfile;
import us.nusun.tack.framework.run.AgentRunProjectionManager;
import us.nusun.tack.framework.run.AgentRun;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class PrimitiveExecutor {
    private static final String THOUGHT_CONTENT_SCHEMA = "classpath:schemas/thought-content-result-schema.json";
    private static final String ACT_SYNTHESIS_SCHEMA = "classpath:schemas/act-synthesis-result-schema.json";

    private final ObjectMapper objectMapper;
    private final ProducerTemplate llm;
    private final PersonaCatalog personas;
    private final ToolProfileRegistry toolProfiles;
    private final AgentRunProjectionManager projections;
    private final PrimitivePrompts prompts;

    public PrimitiveExecutor(ObjectMapper objectMapper, ProducerTemplate llm, PersonaCatalog personas,
                             ToolProfileRegistry toolProfiles,
                             AgentRunProjectionManager projections) {
        this.objectMapper = objectMapper;
        this.llm = llm;
        this.personas = personas;
        this.toolProfiles = toolProfiles;
        this.projections = projections;
        this.prompts = new PrimitivePrompts(objectMapper, personas);
    }

    public record ActRequest(String id, String prompt, String toolProfile, String lane) {
    }

    public record DecideRequest(String id, String prompt, String choices, String lane) {
    }

    public record CreateRequest(
            String id,
            String prompt,
            String schemaId,
            String schema,
            String lane) {
    }

    public record ThoughtEnterRequest(String name, String purpose, int maxTokens) {
    }

    public record ThoughtExitRequest(String name, String lane) {
    }

    public static final class FrameworkPrimitiveException extends RuntimeException {
        public FrameworkPrimitiveException(String message) { super(message); }
        public FrameworkPrimitiveException(String message, Throwable cause) { super(message, cause); }
    }

    public void act(Exchange exchange, ActRequest request) throws Exception {
        ThoughtStack stack = ThoughtStack.on(exchange);
        ThoughtFrame leaf = requireLeaf(stack, "tack:act");
        AgentRun run = currentRun(exchange);
        String toolProfileId = firstNonBlank(request.toolProfile(), ToolProfileRegistry.NONE_ID);
        ToolProfile profile = toolProfiles.getRequired(toolProfileId);
        markCurrent(exchange, "act", request.id());

        LlmJobResult toolResult = callLlm(
                exchange,
                llmRequest(exchange, request.lane(), LlmPassType.ACTION, request.prompt(),
                        prompts.renderToolPassPrompt(exchange, request, profile), null, profile.id()),
                "tool/action pass");
        Instant observedAt = Instant.now();
        projections.record(exchange, "TOOL_PASS_COMPLETED");

        LlmJobResult synthesisResult = callLlm(
                exchange,
                llmRequest(exchange, request.lane(), LlmPassType.SYNTHESIS, request.prompt(),
                        prompts.renderActSynthesisPrompt(exchange, request, leaf, toolResult, observedAt),
                        ACT_SYNTHESIS_SCHEMA, profile.id()),
                "act thought synthesis pass");
        projections.record(exchange, "SYNTHESIS_PASS_COMPLETED");
        ActSynthesisResult synthesis = parse(synthesisResult.content(), ActSynthesisResult.class);
        if (synthesis.content() == null || synthesis.content().isBlank()) {
            throw new FrameworkPrimitiveException("Act synthesis returned blank thought content");
        }
        ThoughtFrame rewritten = stack.rewrite(leaf.id(), synthesis.content(), Instant.now());
        recordThoughtRewritten(exchange, rewritten, "act", request.id());

        StepResult result = new StepResult(
                request.id(),
                rewritten.content(),
                synthesis.summary(),
                synthesis.outcome(),
                synthesis.durableProgress(),
                synthesis.confidence());
        run.apply(result);
        exchange.setProperty("act." + request.id() + ".outcome", result.outcome());
        exchange.setProperty("act." + request.id() + ".durableProgress", result.durableProgress());
        exchange.setProperty("act." + request.id() + ".confidence", result.confidence());
        exchange.setProperty("act." + request.id() + ".summary", result.summary());
        exchange.getMessage().setBody(result);
        projections.recordPrimitiveCompleted(exchange, "ACT_COMPLETED", "act", request.id(), result.summary());
    }

    public void decide(Exchange exchange, DecideRequest request) throws Exception {
        List<String> choices = decisionChoices(request.choices());
        String schema = decisionSchema(choices);
        markCurrent(exchange, "decide", request.id());
        LlmJobResult llmResult = callLlm(
                exchange,
                llmRequest(exchange, request.lane(), LlmPassType.DECISION, request.prompt(),
                        prompts.renderDecisionPrompt(exchange, request, choices), schema, null),
                "decision synthesis pass");
        projections.record(exchange, "SYNTHESIS_PASS_COMPLETED");
        DecisionAnswer answer = parse(llmResult.content(), DecisionAnswer.class);
        DecisionResult result = new DecisionResult(request.id(), answer.result(), answer.confidence(), answer.reason());
        if (result.result() == null || !choices.contains(result.result())) {
            throw new FrameworkPrimitiveException("Decision result " + result.result()
                    + " is not one of the allowed choices " + choices);
        }
        currentRun(exchange).apply(result);
        exchange.setProperty("decisions." + result.decisionId(), result);
        exchange.setProperty("decision." + result.decisionId() + ".result", result.result());
        exchange.getMessage().setBody(result);
        projections.recordPrimitiveCompleted(
                exchange,
                "DECISION_COMPLETED",
                "decide",
                request.id(),
                "result: " + result.result() + "\nreason: " + result.reason());
    }

    public void create(Exchange exchange, CreateRequest request) throws Exception {
        SchemaContract schema = SchemaContract.resolve(exchange.getContext(), objectMapper, request.schema());
        markCurrent(exchange, "create", request.id());
        LlmJobResult llmResult = callLlm(
                exchange,
                llmRequest(exchange, request.lane(), LlmPassType.CREATION, request.prompt(),
                        prompts.renderCreatePrompt(exchange, request, schema), schema.json(), null),
                "create synthesis pass");
        projections.record(exchange, "SYNTHESIS_PASS_COMPLETED");
        JsonNode object = objectMapper.readTree(llmResult.content());
        CreateResult result = new CreateResult(request.id(), firstNonBlank(request.schemaId(), "caller-schema"), object);
        currentRun(exchange).apply(result);
        exchange.setProperty("creates." + result.createId(), result);
        exchange.getMessage().setBody(object.toString());
        projections.recordPrimitiveCompleted(exchange, "CREATE_COMPLETED", "create", request.id(), object.toString());
    }

    public void enterThought(Exchange exchange, ThoughtEnterRequest request) {
        markCurrent(exchange, "thought-enter", request.name());
        if (request.purpose() == null || request.purpose().isBlank()) {
            throw new IllegalArgumentException("thought purpose is required");
        }
        if (request.maxTokens() <= 0) {
            throw new IllegalArgumentException("thought maxTokens must be positive");
        }
        String id = "t_" + UUID.randomUUID().toString().substring(0, 8);
        ThoughtFrame frame = new ThoughtFrame(id, request.name(), request.purpose(), request.maxTokens(), "", Instant.now());
        ThoughtStack.on(exchange).push(frame);
        var details = thoughtEventDetails(frame);
        details.put("purpose", frame.context());
        details.put("maxTokens", frame.maxTokens());
        projections.record(exchange, "THOUGHT_ENTERED", details);
        exchange.getMessage().setBody(frame);
    }

    public void exitThought(Exchange exchange, ThoughtExitRequest request) throws Exception {
        markCurrent(exchange, "thought-exit", request.name());
        ThoughtStack stack = ThoughtStack.on(exchange);
        ThoughtFrame child = stack.leaf();
        if (child == null) {
            throw new IllegalStateException("Cannot exit thought " + request.name() + " because no thought is active");
        }
        if (!child.name().equals(request.name())) {
            throw new IllegalStateException("Cannot exit thought " + request.name() + " because the current leaf is " + child.name());
        }
        ThoughtFrame parent = stack.parentOfLeaf();
        if (parent == null) {
            ThoughtFrame popped = stack.pop();
            recordThoughtExited(exchange, popped, true, false);
            exchange.getMessage().setBody(popped);
            return;
        }
        if (child.content() == null || child.content().isBlank()) {
            ThoughtFrame popped = stack.pop();
            recordThoughtExited(exchange, popped, false, true);
            exchange.getMessage().setBody(popped);
            return;
        }

        LlmJobResult llmResult = callLlm(
                exchange,
                llmRequest(exchange, request.lane(), LlmPassType.CONSOLIDATION, null,
                        prompts.renderConsolidationPrompt(exchange, parent, child), THOUGHT_CONTENT_SCHEMA, null),
                "thought consolidation pass");
        ThoughtContentResult consolidated = parse(llmResult.content(), ThoughtContentResult.class);
        if (consolidated.content() == null || consolidated.content().isBlank()) {
            throw new FrameworkPrimitiveException("Thought consolidation returned blank parent content");
        }
        ThoughtFrame rewrittenParent = stack.rewrite(parent.id(), consolidated.content(), Instant.now());
        recordThoughtRewritten(exchange, rewrittenParent, "thought-exit", request.name());
        ThoughtFrame popped = stack.pop();
        projections.record(exchange, "THOUGHT_CONSOLIDATED", consolidationDetails(popped, rewrittenParent, rewrittenParent.content()));
        recordThoughtExited(exchange, popped, false, false);
        exchange.getMessage().setBody(popped);
    }

    private LlmJobResult callLlm(Exchange exchange, LlmJobEnvelope request, String passDescription) {
        try {
            clearLlmExchangeState(exchange);
            exchange.getMessage().setBody(request);
            Exchange routed = llm.send("direct:llm", exchange);
            if (routed.getException() != null) {
                throw routed.getException();
            }
            return requireSucceeded(routed.getMessage().getBody(LlmJobResult.class), passDescription);
        } catch (Exception e) {
            exchange.setProperty("latestPrimitiveFailure", firstNonBlank(e.getMessage(), e.getClass().getSimpleName()));
            projections.record(exchange, "LLM_PASS_FAILED");
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new FrameworkPrimitiveException("LLM " + passDescription + " failed: " + e.getMessage(), e);
        }
    }

    private void clearLlmExchangeState(Exchange exchange) {
        exchange.removeProperty("llmJob");
        exchange.removeProperty(LlmExecutionContext.PROPERTY);
        exchange.removeProperty("llmQueueFailureHandling");
    }

    private LlmJobResult requireSucceeded(LlmJobResult llm, String pass) {
        if (llm == null) {
            throw new FrameworkPrimitiveException("LLM " + pass + " did not return a result");
        }
        if (llm.status() != LlmJobStatus.SUCCEEDED) {
            throw new FrameworkPrimitiveException("LLM " + pass + " failed: " + firstNonBlank(llm.error(), "unknown error"));
        }
        if (llm.content() == null || llm.content().isBlank()) {
            throw new FrameworkPrimitiveException("LLM " + pass + " returned empty content");
        }
        return llm;
    }

    private LlmJobEnvelope llmRequest(Exchange exchange, String requestedLane, LlmPassType passType,
                                      String primitivePrompt, String prompt, String schema, String toolProfile) {
        AgentRun run = currentRun(exchange);
        String lane = firstNonBlank(requestedLane, currentRoutine(exchange).laneFor(passType),
                RoutineDefinition.FRAMEWORK_DEFAULT_LANE);
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (primitivePrompt != null) {
            metadata.put("primitivePrompt", primitivePrompt);
        }
        if (toolProfile != null) {
            metadata.put("toolProfile", toolProfile);
        }
        if (schema != null && !schema.isBlank()) {
            metadata.put(LlmExecutionContext.FORCE_SCHEMA, true);
        }
        if (passType == LlmPassType.CONSOLIDATION) {
            ThoughtStack stack = ThoughtStack.on(exchange);
            metadata.put("thoughtConsolidationDetails", consolidationDetails(stack.leaf(), stack.parentOfLeaf(), null));
        }
        return new LlmJobEnvelope(null, lane, passType, run.currentPrimitive() + ":" + run.currentId(),
                personas.getRequired(run.personaId()).name(), 0, prompt,
                prompts.primitiveSystemPrompt(exchange, passType), schema, metadata, null);
    }

    private void markCurrent(Exchange exchange, String primitive, String id) {
        AgentRun run = currentRun(exchange);
        run.currentPrimitive(primitive);
        run.currentId(id);
        exchange.removeProperty("latestPrimitiveFailure");
    }

    private void recordThoughtRewritten(Exchange exchange, ThoughtFrame frame, String primitive, String cause) {
        var details = thoughtEventDetails(frame);
        details.put("primitive", primitive);
        details.put("cause", cause);
        projections.record(exchange, "THOUGHT_REWRITTEN", details);
    }

    private void recordThoughtExited(Exchange exchange, ThoughtFrame frame, boolean root, boolean emptyChild) {
        var details = thoughtEventDetails(frame);
        details.put("root", root);
        details.put("emptyChild", emptyChild);
        projections.record(exchange, "THOUGHT_EXITED", details);
    }

    private static LinkedHashMap<String, Object> thoughtEventDetails(ThoughtFrame frame) {
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        details.put("thoughtName", frame.name());
        details.put("thoughtId", frame.id());
        details.put("content", frame.content());
        details.put("timestamp", Instant.now().toString());
        return details;
    }

    private Map<String, Object> consolidationDetails(ThoughtFrame child, ThoughtFrame parent, String resultingParentContent) {
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        details.put("sourceChild", thoughtIdentity(child));
        details.put("destinationParent", thoughtIdentity(parent));
        details.put("finalChildContent", child.content());
        if (resultingParentContent != null) {
            details.put("resultingParentContent", resultingParentContent);
        }
        details.put("timestamp", Instant.now().toString());
        return details;
    }

    private Map<String, Object> thoughtIdentity(ThoughtFrame frame) {
        LinkedHashMap<String, Object> identity = new LinkedHashMap<>();
        identity.put("id", frame.id());
        identity.put("name", frame.name());
        identity.put("purpose", frame.context());
        identity.put("maxTokens", frame.maxTokens());
        identity.put("updatedAt", frame.updatedAt());
        return identity;
    }

    private ThoughtFrame requireLeaf(ThoughtStack stack, String operation) {
        ThoughtFrame leaf = stack.leaf();
        if (leaf == null) {
            throw new IllegalStateException("Cannot run " + operation + " because no active thought is present");
        }
        return leaf;
    }

    private List<String> decisionChoices(String rawChoices) {
        if (rawChoices == null || rawChoices.isBlank()) {
            return List.of("yes", "no");
        }
        List<String> choices = java.util.Arrays.stream(rawChoices.split(","))
                .map(String::strip)
                .filter(choice -> !choice.isBlank())
                .distinct()
                .toList();
        if (choices.size() < 2) {
            throw new IllegalArgumentException("tack:decide requires at least two distinct choices");
        }
        return choices;
    }

    private String decisionSchema(List<String> choices) throws Exception {
        var root = objectMapper.createObjectNode();
        root.put("type", "object");
        var properties = root.putObject("properties");
        var result = properties.putObject("result");
        result.put("type", "string");
        var allowed = result.putArray("enum");
        choices.forEach(allowed::add);
        properties.putObject("confidence").put("type", "number");
        properties.putObject("reason").put("type", "string");
        root.putArray("required")
                .add("result")
                .add("confidence")
                .add("reason");
        root.put("additionalProperties", false);
        return objectMapper.writeValueAsString(root);
    }

    static AgentRun currentRun(Exchange exchange) {
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        if (run == null) throw new IllegalStateException("No AgentRun on exchange");
        return run;
    }

    static RoutineDefinition currentRoutine(Exchange exchange) {
        return exchange.getProperty("routine", RoutineDefinition.class);
    }

    private <T> T parse(String content, Class<T> type) throws Exception {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("LLM returned empty content for " + type.getSimpleName());
        return objectMapper.readValue(content, type);
    }

    private record DecisionAnswer(String result, double confidence, String reason) {}

}
