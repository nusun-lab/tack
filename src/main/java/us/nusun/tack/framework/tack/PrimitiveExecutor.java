package us.nusun.tack.framework.tack;

import static us.nusun.tack.framework.model.FrameworkText.firstNonBlank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import us.nusun.tack.framework.inventory.InferenceLaneRegistry;
import us.nusun.tack.framework.inventory.PersonaCatalog;
import us.nusun.tack.framework.inventory.RoutineCatalog;
import us.nusun.tack.framework.inventory.ToolProfileRegistry;
import us.nusun.tack.framework.llm.ProviderStructuredOutputSupport;
import us.nusun.tack.framework.model.CreateResult;
import us.nusun.tack.framework.llm.SchemaContract;
import us.nusun.tack.framework.llm.LlmExecutionContext;
import us.nusun.tack.framework.model.DecisionResult;
import us.nusun.tack.framework.model.LlmJobEnvelope;
import us.nusun.tack.framework.model.LlmJobResult;
import us.nusun.tack.framework.model.LlmJobStatus;
import us.nusun.tack.framework.model.LlmPassType;
import us.nusun.tack.framework.model.Persona;
import us.nusun.tack.framework.model.RoutineDefinition;
import us.nusun.tack.framework.model.StepResult;
import us.nusun.tack.framework.model.ThoughtFrame;
import us.nusun.tack.framework.model.ToolProfile;
import us.nusun.tack.framework.projection.AgentRunProjectionManager;
import us.nusun.tack.framework.routine.ThoughtStackSupport;
import us.nusun.tack.framework.state.AgentRun;
import us.nusun.tack.framework.state.ThoughtStack;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class PrimitiveExecutor {
    private static final String THOUGHT_CONTENT_SCHEMA = "classpath:schemas/thought-content-result-schema.json";
    private static final String ACT_SYNTHESIS_SCHEMA = "classpath:schemas/act-synthesis-result-schema.json";

    private final ObjectMapper objectMapper;
    private final ThoughtStackSupport thoughts;
    private final PersonaCatalog personas;
    private final RoutineCatalog routines;
    private final ToolProfileRegistry toolProfiles;
    private final InferenceLaneRegistry lanes;
    private final AgentRunProjectionManager projections;
    private final PrimitivePrompts prompts;

    public PrimitiveExecutor(ObjectMapper objectMapper, ThoughtStackSupport thoughts, PersonaCatalog personas,
                             RoutineCatalog routines, ToolProfileRegistry toolProfiles, InferenceLaneRegistry lanes,
                             AgentRunProjectionManager projections) {
        this.objectMapper = objectMapper;
        this.thoughts = thoughts;
        this.personas = personas;
        this.routines = routines;
        this.toolProfiles = toolProfiles;
        this.lanes = lanes;
        this.projections = projections;
        this.prompts = new PrimitivePrompts(objectMapper, thoughts, personas, routines);
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

    public void act(Exchange exchange, ActRequest request, ProducerTemplate llm) throws Exception {
        ThoughtStack stack = stack(exchange);
        ThoughtFrame leaf = requireLeaf(stack, "tack:act");
        AgentRun run = currentRun(exchange);
        String toolProfileId = firstNonBlank(request.toolProfile(), ToolProfileRegistry.NONE_ID);
        ToolProfile profile = toolProfiles.getRequired(toolProfileId);
        markCurrent(exchange, "act", request.id());
        exchange.setProperty("actId", request.id());
        exchange.setProperty("actPrompt", request.prompt());
        exchange.setProperty("toolProfile", toolProfileId);
        exchange.setProperty("toolProfileId", profile.id());
        exchange.setProperty("toolProfileGuidance", profile.promptGuidance().isBlank() ? "(none)" : profile.promptGuidance());

        String actionLaneId = resolveLane(exchange, request.lane(), LlmPassType.ACTION);
        LlmJobResult toolResult = callLlm(
                exchange,
                llm,
                llmRequest(exchange, actionLaneId, LlmPassType.ACTION, "tool", prompts.renderToolPassPrompt(exchange, request, profile), null),
                "tool/action pass");
        Instant observedAt = Instant.now();
        exchange.setProperty("toolPassObservedAt", observedAt);
        exchange.setProperty("toolPassResult", toolResult.content());
        projections.record(exchange, "TOOL_PASS_COMPLETED");

        String synthesisLaneId = resolveLane(exchange, request.lane(), LlmPassType.SYNTHESIS);
        LlmJobResult synthesisResult = callLlm(
                exchange,
                llm,
                llmRequest(exchange, synthesisLaneId, LlmPassType.SYNTHESIS, "synthesis", prompts.renderActSynthesisPrompt(exchange, request, leaf, toolResult, observedAt), ACT_SYNTHESIS_SCHEMA),
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
                synthesis.content(),
                synthesis.summary(),
                synthesis.outcome(),
                synthesis.durableProgress(),
                synthesis.confidence());
        run.apply(result);
        exchange.setProperty("latestStepResult", result);
        exchange.setProperty("act." + request.id() + ".outcome", result.outcome());
        exchange.setProperty("act." + request.id() + ".durableProgress", result.durableProgress());
        exchange.setProperty("act." + request.id() + ".confidence", result.confidence());
        exchange.setProperty("act." + request.id() + ".summary", result.summary());
        exchange.getMessage().setBody(result);
        projections.recordPrimitiveCompleted(exchange, "act", request.id(), result.summary());
        projections.record(exchange, "ACT_COMPLETED");
    }

    public void decide(Exchange exchange, DecideRequest request, ProducerTemplate llm) throws Exception {
        String laneId = resolveLane(exchange, request.lane(), LlmPassType.DECISION);
        List<String> choices = decisionChoices(request.choices());
        String schema = decisionSchema(choices);
        markCurrent(exchange, "decide", request.id());
        exchange.setProperty("decisionId", request.id());
        exchange.setProperty("decisionPrompt", request.prompt());
        exchange.setProperty("decisionChoices", choices);
        LlmJobResult llmResult = callLlm(
                exchange,
                llm,
                llmRequest(exchange, laneId, LlmPassType.DECISION, "decision",
                        prompts.renderDecisionPrompt(exchange, request, choices), schema),
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
                "decide",
                request.id(),
                "result: " + result.result() + "\nreason: " + result.reason());
        projections.record(exchange, "DECISION_COMPLETED");
    }

    public void create(Exchange exchange, CreateRequest request, ProducerTemplate llm) throws Exception {
        String laneId = resolveLane(exchange, request.lane(), LlmPassType.CREATION);
        SchemaContract schema = SchemaContract.resolve(exchange.getContext(), objectMapper, request.schema());
        markCurrent(exchange, "create", request.id());
        exchange.setProperty("createId", request.id());
        exchange.setProperty("schemaId", firstNonBlank(request.schemaId(), "caller-schema"));
        exchange.setProperty("createPrompt", request.prompt());
        LlmJobResult llmResult = callLlm(
                exchange,
                llm,
                llmRequest(exchange, laneId, LlmPassType.CREATION, "create", prompts.renderCreatePrompt(exchange, request, schema), schema.json()),
                "create synthesis pass");
        projections.record(exchange, "SYNTHESIS_PASS_COMPLETED");
        JsonNode object = objectMapper.readTree(llmResult.content());
        CreateResult result = new CreateResult(request.id(), firstNonBlank(request.schemaId(), "caller-schema"), object);
        currentRun(exchange).apply(result);
        exchange.setProperty("creates." + result.createId(), result);
        exchange.setProperty("latestCreateResult", result);
        exchange.getMessage().setBody(object.toString());
        projections.recordPrimitiveCompleted(exchange, "create", request.id(), object.toString());
        projections.record(exchange, "CREATE_COMPLETED");
    }

    public void enterThought(Exchange exchange, ThoughtEnterRequest request) {
        markCurrent(exchange, "thought-enter", request.name());
        ThoughtFrame frame = thoughts.push(exchange, request.name(), request.purpose(), request.maxTokens());
        exchange.getMessage().setBody(frame);
    }

    public void exitThought(Exchange exchange, ThoughtExitRequest request, ProducerTemplate llm) throws Exception {
        markCurrent(exchange, "thought-exit", request.name());
        ThoughtStack stack = stack(exchange);
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

        String laneId = resolveLane(exchange, request.lane(), LlmPassType.CONSOLIDATION);
        LlmJobResult llmResult = callLlm(
                exchange,
                llm,
                llmRequest(exchange, laneId, LlmPassType.CONSOLIDATION, "thought-consolidation", prompts.renderConsolidationPrompt(exchange, parent, child), THOUGHT_CONTENT_SCHEMA),
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

    private LlmJobResult callLlm(Exchange exchange, ProducerTemplate llm, LlmJobEnvelope request, String passDescription) {
        try {
            clearLlmExchangeState(exchange);
            exchange.getMessage().setBody(request);
            Exchange routed = llm.send("direct:llm", exchange);
            if (routed.getException() != null) {
                throw routed.getException();
            }
            return requireSucceeded(exchange, routed.getMessage().getBody(LlmJobResult.class), passDescription);
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
        exchange.removeProperty("llmJobId");
        exchange.removeProperty("llmJob");
        exchange.removeProperty(LlmExecutionContext.PROPERTY);
        exchange.removeProperty("llmResult");
        exchange.removeProperty("llmQueueFailureHandling");
    }

    private LlmJobResult requireSucceeded(Exchange exchange, LlmJobResult llm, String pass) {
        if (llm == null) {
            exchange.setProperty("latestPrimitiveFailure", "LLM " + pass + " did not return a result");
            throw new FrameworkPrimitiveException("LLM " + pass + " did not return a result");
        }
        if (llm.status() != LlmJobStatus.SUCCEEDED) {
            exchange.setProperty("latestPrimitiveFailure", firstNonBlank(llm.error(), "unknown error"));
            throw new FrameworkPrimitiveException("LLM " + pass + " failed: " + firstNonBlank(llm.error(), "unknown error"));
        }
        if (llm.content() == null || llm.content().isBlank()) {
            exchange.setProperty("latestPrimitiveFailure", "empty LLM content");
            throw new FrameworkPrimitiveException("LLM " + pass + " returned empty content");
        }
        return llm;
    }

    private LlmJobEnvelope llmRequest(Exchange exchange, String laneId, LlmPassType passType, String systemContract, String prompt, String schema) {
        AgentRun run = currentRun(exchange);
        Persona persona = personas.getRequired(run.personaId());
        LlmJobEnvelope request = new LlmJobEnvelope(
                null,
                laneId,
                passType,
                primitiveRequester(run),
                persona.name(),
                0,
                prompt,
                prompts.primitiveSystemPrompt(exchange, systemContract),
                schema,
                primitiveMetadata(exchange, passType, schema),
                null);
        return request.withMetadata(LlmExecutionContext.capture(exchange, request, null, null).wireMetadata(request.metadata()));
    }

    private Map<String, Object> primitiveMetadata(Exchange exchange, LlmPassType passType, String schema) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        if (passType == LlmPassType.CONSOLIDATION) {
            ThoughtStack stack = stack(exchange);
            ThoughtFrame child = stack.leaf();
            ThoughtFrame parent = stack.parentOfLeaf();
            if (child != null && parent != null) {
                metadata.put("thoughtConsolidationDetails", consolidationDetails(child, parent, null));
            }
        }
        if (schema != null && !schema.isBlank()) {
            metadata.put(ProviderStructuredOutputSupport.FORCE_PROVIDER_SCHEMA_PROPERTY, true);
        }
        return Map.copyOf(metadata);
    }

    private static String primitiveRequester(AgentRun run) {
        return firstNonBlank(run.currentPrimitive() + ":" + run.currentId(), "framework");
    }

    private String resolveLane(Exchange exchange, String requestedLane, LlmPassType passType) {
        RoutineDefinition routine = currentRoutine(exchange);
        String laneId = firstNonBlank(requestedLane, routine.laneFor(passType), RoutineDefinition.FRAMEWORK_DEFAULT_LANE);
        lanes.getRequired(laneId);
        exchange.setProperty("resolvedLane", laneId);
        exchange.setProperty("laneId", laneId);
        return laneId;
    }

    private void markCurrent(Exchange exchange, String primitive, String id) {
        AgentRun run = currentRun(exchange);
        run.currentPrimitive(primitive);
        run.currentId(id);
        exchange.setProperty("personaId", run.personaId());
        exchange.removeProperty("latestPrimitiveFailure");
        clearPromptProperties(exchange);
    }

    private void clearPromptProperties(Exchange exchange) {
        exchange.removeProperty("actPrompt");
        exchange.removeProperty("decisionPrompt");
        exchange.removeProperty("createPrompt");
        exchange.removeProperty("primitivePrompt");
    }

    private void recordThoughtRewritten(Exchange exchange, ThoughtFrame frame, String primitive, String cause) {
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        details.put("thoughtName", frame.name());
        details.put("thoughtId", frame.id());
        details.put("content", frame.content());
        details.put("primitive", primitive);
        details.put("cause", cause);
        details.put("timestamp", Instant.now().toString());
        projections.record(exchange, "THOUGHT_REWRITTEN", details);
    }

    private void recordThoughtExited(Exchange exchange, ThoughtFrame frame, boolean root, boolean emptyChild) {
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        details.put("thoughtName", frame.name());
        details.put("thoughtId", frame.id());
        details.put("content", frame.content());
        details.put("root", root);
        details.put("emptyChild", emptyChild);
        details.put("timestamp", Instant.now().toString());
        projections.record(exchange, "THOUGHT_EXITED", details);
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

    private RoutineDefinition currentRoutine(Exchange exchange) {
        return routines.getRequired(currentRun(exchange).routineId());
    }

    private ThoughtStack stack(Exchange exchange) {
        return thoughts.stack(exchange);
    }

    private <T> T parse(String content, Class<T> type) throws Exception {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("LLM returned empty content for " + type.getSimpleName());
        return objectMapper.readValue(content, type);
    }

    private record DecisionAnswer(String result, double confidence, String reason) {}

}
