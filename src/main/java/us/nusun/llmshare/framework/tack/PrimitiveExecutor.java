package us.nusun.llmshare.framework.tack;

import static us.nusun.llmshare.framework.model.FrameworkText.firstNonBlank;
import static us.nusun.llmshare.framework.model.FrameworkText.putIfNonBlank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import us.nusun.llmshare.framework.inventory.InferenceLaneRegistry;
import us.nusun.llmshare.framework.inventory.PersonaCatalog;
import us.nusun.llmshare.framework.inventory.RoutineCatalog;
import us.nusun.llmshare.framework.inventory.ToolProfileRegistry;
import us.nusun.llmshare.framework.llm.ProviderStructuredOutputSupport;
import us.nusun.llmshare.framework.model.CreateResult;
import us.nusun.llmshare.framework.model.DecisionResult;
import us.nusun.llmshare.framework.model.LlmJobRequest;
import us.nusun.llmshare.framework.model.LlmJobResult;
import us.nusun.llmshare.framework.model.LlmJobStatus;
import us.nusun.llmshare.framework.model.LlmPassType;
import us.nusun.llmshare.framework.model.Mission;
import us.nusun.llmshare.framework.model.Persona;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.model.StepResult;
import us.nusun.llmshare.framework.model.ThoughtFrame;
import us.nusun.llmshare.framework.model.ToolProfile;
import us.nusun.llmshare.framework.projection.AgentRunProjectionManager;
import us.nusun.llmshare.framework.routine.ThoughtStackSupport;
import us.nusun.llmshare.framework.service.MissionRosterService;
import us.nusun.llmshare.framework.state.AgentRun;
import us.nusun.llmshare.framework.state.AgentRunStore;
import us.nusun.llmshare.framework.state.ThoughtStack;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class PrimitiveExecutor {
    private static final String THOUGHT_CONTENT_SCHEMA = "classpath:schemas/thought-content-result-schema.json";
    private static final String ACT_SYNTHESIS_SCHEMA = "classpath:schemas/act-synthesis-result-schema.json";

    @Inject ObjectMapper objectMapper;
    @Inject ThoughtStackSupport thoughts;
    @Inject PersonaCatalog personas;
    @Inject RoutineCatalog routines;
    @Inject ToolProfileRegistry toolProfiles;
    @Inject MissionRosterService missionRoster;
    @Inject InferenceLaneRegistry lanes;
    @Inject AgentRunProjectionManager projections;

    public record ActRequest(String id, String prompt, String toolProfile, String lane) {
    }

    public record DecideRequest(String id, String prompt, String choices, String lane) {
    }

    public record CreateRequest(
            String id,
            String prompt,
            String schemaId,
            String schema,
            String schemaJson,
            String schemaUri,
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
        String laneId = resolveLane(exchange, request.lane());
        String toolProfileId = firstNonBlank(request.toolProfile(), ToolProfileRegistry.NONE_ID);
        ToolProfile profile = toolProfiles().getRequired(toolProfileId);
        markCurrent(exchange, "act", request.id());
        exchange.setProperty("actId", request.id());
        exchange.setProperty("actPrompt", request.prompt());
        exchange.setProperty("toolProfile", toolProfileId);
        exchange.setProperty("toolProfileId", profile.id());
        exchange.setProperty("toolProfileGuidance", profile.promptGuidance().isBlank() ? "(none)" : profile.promptGuidance());
        projections.record(exchange, "ACT_STARTED");

        projections.record(exchange, "TOOL_PASS_QUEUED");
        LlmJobResult toolResult = callLlm(
                exchange,
                llm,
                llmRequest(exchange, laneId, LlmPassType.TOOL, "tool", renderToolPassPrompt(exchange, request, profile), null),
                "tool/action pass");
        Instant observedAt = Instant.now();
        exchange.setProperty("toolPassObservedAt", observedAt);
        exchange.setProperty("toolPassResult", toolResult.content());
        projections.record(exchange, "TOOL_PASS_COMPLETED");

        projections.record(exchange, "SYNTHESIS_PASS_QUEUED");
        LlmJobResult synthesisResult = callLlm(
                exchange,
                llm,
                llmRequest(exchange, laneId, LlmPassType.SYNTHESIS, "synthesis", renderActSynthesisPrompt(exchange, request, leaf, toolResult, observedAt), ACT_SYNTHESIS_SCHEMA),
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
                synthesis.confidence(),
                List.of());
        run.apply(result);
        exchange.setProperty("latestStepResult", result);
        exchange.setProperty("act." + request.id() + ".outcome", result.outcome());
        exchange.setProperty("act." + request.id() + ".durableProgress", result.durableProgress());
        exchange.setProperty("act." + request.id() + ".confidence", result.confidence());
        exchange.setProperty("act." + request.id() + ".summary", result.summary());
        exchange.getMessage().setBody(result);
        projections.record(exchange, "ACT_COMPLETED");
    }

    public void decide(Exchange exchange, DecideRequest request, ProducerTemplate llm) throws Exception {
        String laneId = resolveLane(exchange, request.lane());
        List<String> choices = decisionChoices(request.choices());
        String schema = decisionSchema(choices);
        markCurrent(exchange, "decide", request.id());
        exchange.setProperty("decisionId", request.id());
        exchange.setProperty("decisionPrompt", request.prompt());
        exchange.setProperty("decisionChoices", choices);
        projections.record(exchange, "DECIDE_STARTED");
        projections.record(exchange, "SYNTHESIS_PASS_QUEUED");
        LlmJobResult llmResult = callLlm(
                exchange,
                llm,
                llmRequest(exchange, laneId, LlmPassType.SYNTHESIS, "decision",
                        renderDecisionPrompt(exchange, request), schema),
                "decision synthesis pass");
        projections.record(exchange, "SYNTHESIS_PASS_COMPLETED");
        DecisionResult result = parse(llmResult.content(), DecisionResult.class);
        if (result.decisionId() == null || !result.decisionId().equals(request.id())) {
            throw new FrameworkPrimitiveException("Decision result id " + result.decisionId()
                    + " does not match requested id " + request.id());
        }
        if (result.result() == null || !choices.contains(result.result())) {
            throw new FrameworkPrimitiveException("Decision result " + result.result()
                    + " is not one of the allowed choices " + choices);
        }
        currentRun(exchange).apply(result);
        exchange.setProperty("decisions." + result.decisionId(), result);
        exchange.setProperty("decision." + result.decisionId() + ".result", result.result());
        exchange.getMessage().setBody(result);
        projections.record(exchange, "DECISION_COMPLETED");
    }

    public void create(Exchange exchange, CreateRequest request, ProducerTemplate llm) throws Exception {
        String laneId = resolveLane(exchange, request.lane());
        ResolvedSchema schema = resolveCreateSchema(request);
        markCurrent(exchange, "create", request.id());
        exchange.setProperty("createId", request.id());
        exchange.setProperty("schemaId", firstNonBlank(request.schemaId(), "caller-schema"));
        exchange.setProperty("createPrompt", request.prompt());
        exchange.setProperty("createSchema", schema.raw());
        exchange.setProperty("createSchemaJson", schema.json());
        exchange.setProperty("createSchemaUri", schema.uri());
        projections.record(exchange, "CREATE_STARTED");
        projections.record(exchange, "SYNTHESIS_PASS_QUEUED");
        LlmJobResult llmResult = callLlm(
                exchange,
                llm,
                llmRequest(exchange, laneId, LlmPassType.SYNTHESIS, "create", renderCreatePrompt(exchange, request, schema), schema.uri()),
                "create synthesis pass");
        projections.record(exchange, "SYNTHESIS_PASS_COMPLETED");
        JsonNode object = mapper().readTree(llmResult.content());
        validateAgainstSchema(schema.json(), object);
        CreateResult result = new CreateResult(request.id(), firstNonBlank(request.schemaId(), "caller-schema"), object);
        currentRun(exchange).apply(result);
        exchange.setProperty("creates." + result.createId(), result);
        exchange.setProperty("latestCreateResult", result);
        exchange.getMessage().setBody(object.toString());
        projections.record(exchange, "CREATE_COMPLETED");
    }

    public void enterThought(Exchange exchange, ThoughtEnterRequest request) {
        markCurrent(exchange, "thought-enter", request.name());
        ThoughtFrame frame = thoughts().push(exchange, request.name(), request.purpose(), request.maxTokens());
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

        String laneId = resolveLane(exchange, request.lane());
        projections.record(exchange, "THOUGHT_CONSOLIDATION_STARTED", consolidationDetails(child, parent, null));
        LlmJobResult llmResult = callLlm(
                exchange,
                llm,
                llmRequest(exchange, laneId, LlmPassType.SYNTHESIS, "thought-consolidation", renderConsolidationPrompt(exchange, parent, child), THOUGHT_CONTENT_SCHEMA),
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

    private LlmJobResult callLlm(Exchange exchange, ProducerTemplate llm, LlmJobRequest request, String passDescription) {
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

    private LlmJobRequest llmRequest(Exchange exchange, String laneId, LlmPassType passType, String systemContract, String prompt, String schema) {
        AgentRun run = currentRun(exchange);
        Persona persona = personas.getRequired(run.personaId());
        return new LlmJobRequest(
                null,
                laneId,
                passType,
                primitiveRequester(run),
                persona.name(),
                0,
                prompt,
                primitiveSystemPrompt(exchange, systemContract),
                schema,
                frameworkMetadata(exchange, laneId, passType, schema),
                null);
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
        putIfNonBlank(metadata, "toolProfile", exchange.getProperty("toolProfile", String.class));
        putIfNonBlank(metadata, "laneId", laneId);
        putIfNonBlank(metadata, "passType", passType == null ? null : passType.name());
        if (schema != null && !schema.isBlank()) {
            metadata.put(ProviderStructuredOutputSupport.FORCE_PROVIDER_SCHEMA_PROPERTY, true);
        }
        return Map.copyOf(metadata);
    }

    private static String primitiveRequester(AgentRun run) {
        return firstNonBlank(run.currentPrimitive() + ":" + run.currentId(), "framework");
    }

    private String resolveLane(Exchange exchange, String requestedLane) {
        RoutineDefinition routine = currentRoutine(exchange);
        String laneId = firstNonBlank(requestedLane, routine.defaultLane(), RoutineDefinition.FRAMEWORK_DEFAULT_LANE);
        lanes().getRequired(laneId);
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
                    - Return only valid JSON matching the supplied schema.
                    - This pass has no tools; do not call tools, reason about tool availability, or retry the action.
                    - The preceding action has already completed; do not repeat it.
                    - Treat the supplied latest observation as the newest evidence.
                    - Rewrite the current thought as current-state memory, not hidden chain-of-thought.
                    - Evaluate only what the completed act actually accomplished; do not infer success from intent.
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
            case "thought-consolidation" -> """

                    Contract:
                    - Consolidate a completed child thought into its parent.
                    - Use only supplied context; this pass has no tools.
                    - Return only valid JSON matching the supplied schema.
                    - The framework has already selected the parent; do not reference framework ids in content.
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

    private String renderToolPassPrompt(Exchange exchange, ActRequest request, ToolProfile profile) {
        return """
                %s

                ## Step-Level Context
                - Primitive: act
                - Act id: %s
                - Tool profile: %s

                ### Current thought:
                %s

                ### Ancestor context:
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
                safeText(request.id()),
                safeText(profile.id()),
                renderCurrentThought(stack(exchange).leaf()),
                renderAncestorThoughts(stack(exchange).frames()),
                profile.promptGuidance().isBlank() ? "(none)" : profile.promptGuidance(),
                safeText(request.prompt()));
    }

    private String renderActSynthesisPrompt(Exchange exchange, ActRequest request, ThoughtFrame leaf, LlmJobResult toolResult, Instant observedAt) {
        return """
                %s

                ## Step-Level Context
                - Primitive: act synthesis
                - Act id: %s

                ### Act objective:
                %s

                ### Tool evidence observedAt: %s
                %s

                ### Current leaf thought purpose:
                %s

                ### Current leaf existing content:
                %s

                ### Current leaf token budget:
                %s

                ### Useful ancestor context:
                %s

                ## SYNTHESIS PROMPT - DO THIS NOW
                Rewrite the current thought as complete, compact, current memory and evaluate the completed act.

                Memory:
                - Preserve still-relevant prior content.
                - Incorporate durable facts learned during this operation.
                - Resolve conflicts in favor of newer evidence.
                - Exclude transient reasoning, raw tool mechanics, and irrelevant detail.
                - Fit the stated thought purpose and token budget.

                Outcome:
                - SUCCEEDED: the requested act completed as intended.
                - PARTIAL: useful work occurred but the requested act still has a directly actionable remainder.
                - BLOCKED: the requested act cannot continue without another role, external event, or missing authoritative input.
                - NOOP: the act completed correctly but found nothing to change or do.
                - FAILED: the act did not accomplish its requested objective.

                Durable progress:
                - true only when this act materially changed durable external mission state, completed a required handoff,
                  produced validated implementation evidence, or resolved an actionable condition.
                - false for reads, understanding, unchanged verification, generic status/chat, or rediscovering existing state.
                - detecting a blocker alone is not durable progress; recording the required durable blocker/handoff may be.

                Confidence is telemetry only. Estimate confidence in this evaluation from 0.0 to 1.0; it does not authorize actions.

                Return JSON only:
                {"content":"One coherent updated thought.","summary":"One compact statement of what this act accomplished.","outcome":"SUCCEEDED|PARTIAL|BLOCKED|NOOP|FAILED","durableProgress":true,"confidence":0.95}
                """.formatted(
                missionLevelContext(exchange),
                safeText(request.id()),
                safeText(request.prompt()),
                observedAt,
                safeText(toolResult.content()),
                safeText(leaf.context()),
                safeText(leaf.content()),
                leaf.maxTokens(),
                renderAncestorThoughts(stack(exchange).frames()));
    }

    private String renderDecisionPrompt(Exchange exchange, DecideRequest request) {
        List<String> choices = decisionChoices(request.choices());
        return """
                %s

                ## Step-Level Context
                - Primitive: decide
                - Decision id: %s
                - Allowed answers: %s

                ### Active thoughts:
                %s

                ### Latest step result:
                %s

                ## Rules / Execution Guidance
                - Answer only the highlighted decision question.
                - Choose exactly one allowed answer.
                - Do not invent missing evidence or perform work outside this decision.
                - If the answers are yes and no, prefer no when evidence is incomplete, stale, or blocked.

                ## DECISION PROMPT - ANSWER THIS NOW
                Question: %s

                ## Response Format
                Return only decisionId, result, confidence, and reason.
                """.formatted(
                missionLevelContext(exchange),
                safeText(request.id()),
                String.join(", ", choices),
                renderThoughtFramesForModel(stack(exchange).frames()),
                renderStepResultForModel(currentRun(exchange).latestStepResult()),
                safeText(request.prompt()));
    }

    private String renderCreatePrompt(Exchange exchange, CreateRequest request, ResolvedSchema schema) {
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
                safeText(request.id()),
                renderThoughtFramesForModel(stack(exchange).frames()),
                safeText(request.prompt()),
                schema.json());
    }

    private String renderConsolidationPrompt(Exchange exchange, ThoughtFrame parent, ThoughtFrame child) {
        return """
                %s

                ## Parent thought
                name: %s
                purpose: %s
                maxTokens: %s
                updatedAt: %s
                existing content:
                %s

                ## Child thought being exited
                name: %s
                purpose: %s
                maxTokens: %s
                updatedAt: %s
                final content:
                %s

                ## CONSOLIDATION PROMPT - DO THIS NOW
                Rewrite the parent as one coherent, current durable thought.

                Preserve still-relevant parent memory.
                Promote only child information useful beyond the child scope.
                Replace stale or conflicting information with newer evidence.
                Discard temporary, redundant, scope-local, or implementation-specific detail.
                Fit the parent purpose and token budget.

                Do not expose framework ids or expired child ids in the parent content.

                Return JSON only:
                {"content":"One coherent replacement parent thought."}
                """.formatted(
                missionLevelContext(exchange),
                safeText(parent.name()),
                safeText(parent.context()),
                parent.maxTokens(),
                parent.updatedAt(),
                safeText(parent.content()),
                safeText(child.name()),
                safeText(child.context()),
                child.maxTokens(),
                child.updatedAt(),
                safeText(child.content()));
    }

    private String missionLevelContext(Exchange exchange) {
        Mission mission = exchange.getProperty("mission", Mission.class);
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
                mission == null ? "(none)" : safeText(mission.goal()),
                renderAgentRunInput(currentRun(exchange)),
                renderRoutineSummary(currentRoutine(exchange)),
                missionRoster().missionRosterPrompt(exchange));
    }

    private String renderCurrentThought(ThoughtFrame frame) {
        if (frame == null) {
            return "(none)";
        }
        return """
                name: %s
                purpose: %s
                maxTokens: %s
                updatedAt: %s
                content: %s
                """.formatted(
                safeText(frame.name()),
                safeText(frame.context()),
                frame.maxTokens(),
                frame.updatedAt(),
                safeText(frame.content())).strip();
    }

    private String renderAncestorThoughts(List<ThoughtFrame> frames) {
        if (frames.size() <= 1) {
            return "(none)";
        }
        return renderThoughtFramesForModel(frames.subList(0, frames.size() - 1));
    }

    private String renderThoughtFramesForModel(List<ThoughtFrame> frames) {
        if (frames == null || frames.isEmpty()) {
            return "(none)";
        }
        StringBuilder out = new StringBuilder();
        for (ThoughtFrame frame : frames) {
            out.append("- name: ").append(safeText(frame.name())).append('\n');
            out.append("  purpose: ").append(safeText(frame.context())).append('\n');
            out.append("  maxTokens: ").append(frame.maxTokens()).append('\n');
            out.append("  updatedAt: ").append(frame.updatedAt()).append('\n');
            out.append("  content: ").append(safeText(frame.content())).append('\n');
        }
        return out.toString();
    }

    private String renderStepResultForModel(StepResult result) {
        if (result == null) {
            return "null";
        }
        LinkedHashMap<String, Object> compact = new LinkedHashMap<>();
        compact.put("actId", result.actId());
        compact.put("summary", result.summary());
        compact.put("outcome", result.outcome());
        compact.put("durableProgress", result.durableProgress());
        compact.put("confidence", result.confidence());
        return asJson(compact);
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
        var root = mapper().createObjectNode();
        root.put("type", "object");
        var properties = root.putObject("properties");
        properties.putObject("decisionId").put("type", "string");
        var result = properties.putObject("result");
        result.put("type", "string");
        var allowed = result.putArray("enum");
        choices.forEach(allowed::add);
        properties.putObject("confidence").put("type", "number");
        properties.putObject("reason").put("type", "string");
        root.putArray("required")
                .add("decisionId")
                .add("result")
                .add("confidence")
                .add("reason");
        root.put("additionalProperties", false);
        return mapper().writeValueAsString(root);
    }

    private ResolvedSchema resolveCreateSchema(CreateRequest request) throws Exception {
        String explicitJson = request.schemaJson();
        String explicitUri = request.schemaUri();
        String raw = request.schema();
        if (explicitJson != null && !explicitJson.isBlank()) {
            return new ResolvedSchema(firstNonBlank(raw, explicitJson), explicitJson, explicitUri);
        }
        String candidate = firstNonBlank(explicitUri, raw);
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalArgumentException("Missing required schema parameter for tack:create");
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
        JsonNode schema = mapper().readTree(schemaJson);
        List<Error> errors = SchemaRegistry
                .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(schema)
                .validate(object);
        if (!errors.isEmpty()) {
            String message = errors.stream().map(Error::getMessage).sorted().collect(Collectors.joining("; "));
            throw new FrameworkPrimitiveException("CreateResult schema validation failed: " + message);
        }
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

    private String asJson(Object value) {
        if (value == null) return "null";
        try {
            return mapper().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
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

    private ThoughtStack stack(Exchange exchange) {
        return thoughts().stack(exchange);
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

    private ThoughtStackSupport thoughts() {
        if (thoughts == null) {
            thoughts = new ThoughtStackSupport();
        }
        return thoughts;
    }

    private ObjectMapper mapper() {
        if (objectMapper == null) {
            objectMapper = new ObjectMapper();
        }
        return objectMapper;
    }

    private <T> T parse(String content, Class<T> type) throws Exception {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("LLM returned empty content for " + type.getSimpleName());
        return mapper().readValue(content, type);
    }

    private static String safeText(String value) {
        return value == null || value.isBlank() ? "(none)" : value.strip();
    }

    private static String compact(String value) {
        if (value == null || value.isBlank()) return null;
        return value.strip().replaceAll("\\s+", " ");
    }

    private record ResolvedSchema(String raw, String json, String uri) {
    }
}
