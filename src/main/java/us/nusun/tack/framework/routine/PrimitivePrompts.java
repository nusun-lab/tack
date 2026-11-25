package us.nusun.tack.framework.routine;

import static us.nusun.tack.framework.routine.PrimitiveExecutor.currentRoutine;
import static us.nusun.tack.framework.routine.PrimitiveExecutor.currentRun;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import us.nusun.tack.framework.persona.PersonaCatalog;
import us.nusun.tack.framework.inference.LlmJobResult;
import us.nusun.tack.framework.inference.LlmPassType;
import us.nusun.tack.framework.run.Mission;
import us.nusun.tack.framework.persona.Persona;
import us.nusun.tack.framework.tool.ToolProfile;
import us.nusun.tack.framework.run.AgentRun;
import us.nusun.tack.framework.routine.PrimitiveExecutor.ActRequest;
import us.nusun.tack.framework.routine.PrimitiveExecutor.CreateRequest;
import us.nusun.tack.framework.routine.PrimitiveExecutor.DecideRequest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PrimitivePrompts {
    private final ObjectMapper objectMapper;
    private final PersonaCatalog personas;

    PrimitivePrompts(ObjectMapper objectMapper, PersonaCatalog personas) {
        this.objectMapper = objectMapper;
        this.personas = personas;
    }

    String primitiveSystemPrompt(Exchange exchange, LlmPassType passType) {
        Persona persona = personas.getRequired(currentRun(exchange).personaId());
        return renderPersonaBehavior(persona)
                + switch (passType) {
            case ACTION -> """

                    Tool access:
                    - You have access to only one external tool profile during this pass.
                    - Framework-local tools such as mission_roster may also be available; use them only when the act
                      requires current mission-team state.

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
            case SYNTHESIS -> """

                    Contract:
                    - Synthesize the completed action result into the required JSON.
                    - Return only valid JSON matching the supplied schema.
                    - This pass has no tools; do not call tools, reason about tool availability, or retry the action.
                    - The preceding action has already completed; do not repeat it.
                    - Treat the supplied latest observation as the newest evidence.
                    - Rewrite the current thought as current-state memory, not hidden chain-of-thought.
                    - Evaluate only what the completed act actually accomplished; do not infer success from intent.
                    """;
            case DECISION -> """

                    Contract:
                    - Answer one narrow routine control-flow question.
                    - Use only supplied context; this pass has no tools.
                    - Return only a DecisionResult JSON object.
                    """;
            case CREATION -> """

                    Contract:
                    - Create caller-schema-shaped JSON for the highlighted prompt only.
                    - Use only supplied context; this pass has no tools.
                    - Return only a JSON object matching the caller schema.
                    """;
            case CONSOLIDATION -> """

                    Contract:
                    - Consolidate a completed child thought into its parent.
                    - Use only supplied context; this pass has no tools.
                    - Return only valid JSON matching the supplied schema.
                    - The framework has already selected the parent; do not reference framework ids in content.
                    """;
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

    String renderToolPassPrompt(Exchange exchange, ActRequest request, ToolProfile profile) {
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
                renderCurrentThought(ThoughtStack.on(exchange).leaf()),
                renderAncestorThoughts(ThoughtStack.on(exchange).frames()),
                profile.promptGuidance().isBlank() ? "(none)" : profile.promptGuidance(),
                safeText(request.prompt()));
    }

    String renderActSynthesisPrompt(Exchange exchange, ActRequest request, ThoughtFrame leaf, LlmJobResult toolResult, Instant observedAt) {
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
                renderAncestorThoughts(ThoughtStack.on(exchange).frames()));
    }

    String renderDecisionPrompt(Exchange exchange, DecideRequest request, List<String> choices) {
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
                Return only result, confidence, and reason.
                """.formatted(
                missionLevelContext(exchange),
                safeText(request.id()),
                String.join(", ", choices),
                renderThoughtFramesForModel(ThoughtStack.on(exchange).frames()),
                renderStepResultForModel(currentRun(exchange).latestStepResult()),
                safeText(request.prompt()));
    }

    String renderCreatePrompt(Exchange exchange, CreateRequest request, String schema) {
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
                renderThoughtFramesForModel(ThoughtStack.on(exchange).frames()),
                safeText(request.prompt()),
                schema);
    }

    String renderConsolidationPrompt(Exchange exchange, ThoughtFrame parent, ThoughtFrame child) {
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
                %s""".formatted(
                mission == null ? "(none)" : safeText(mission.goal()),
                renderAgentRunInput(currentRun(exchange)),
                renderRoutineSummary(currentRoutine(exchange)));
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
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static String safeText(String value) {
        return value == null || value.isBlank() ? "(none)" : value.strip();
    }

    private static String compact(String value) {
        if (value == null || value.isBlank()) return null;
        return value.strip().replaceAll("\\s+", " ");
    }

}
