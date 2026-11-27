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
    private static final String MEMORY_GUIDANCE = """
            Write the complete updated working memory as one plain-text paragraph with inline labels in `content`. This replaces the existing thought, so omissions are lost.
            Use the thought purpose as a checklist. Carry forward unchanged notes that still serve it and add relevant verified facts and open conditions.
            Copy exact identifiers verbatim with what they identify. Change established facts only when supported by new evidence. Keep useful detail within the available budget.
            """.strip();

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

                    Complete the current act with targeted tool calls. Use verified context first and gather
                    enough evidence to finish. Stop when the objective is met or an actual blocker is established.
                    Check each result before issuing a call that depends on it. Copy locators verbatim from the
                    latest direct evidence. Follow tool schemas, using {} for parameterless tools. Examples and
                    placeholders are instructional text.
                    """;
            case SYNTHESIS -> """

                    Maintain the thought for the next step. Put the working memory in `content` and evaluate the latest act separately.
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

                    Maintain the parent's working memory using relevant evidence from the child.
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

                ## Act
                %s

                Report briefly using these lines when applicable:
                Verified / exact locators:
                Changed:
                Unknown or blocked / next actionable condition:
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

                ### Thought purpose
                %s

                ### Existing thought
                %s

                ### Thought token budget
                %s

                ### Ancestor context
                %s

                ### Act objective
                %s

                ### New observations from the act (%s)
                %s

                Evaluate only the latest act in `summary` (brief evidence), `outcome` (SUCCEEDED, PARTIAL, BLOCKED, NOOP,
                or FAILED), `durableProgress` (boolean, false for reads), and `confidence` (number from 0 to 1).

                Update the existing thought with relevant new observations according to its purpose.
                %s
                """.formatted(
                missionLevelContext(exchange),
                safeText(request.id()),
                safeText(leaf.context()),
                safeText(leaf.content()),
                leaf.maxTokens(),
                renderAncestorThoughts(ThoughtStack.on(exchange).frames()),
                safeText(request.prompt()),
                observedAt,
                safeText(toolResult.content()),
                MEMORY_GUIDANCE);
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

                ## Decision
                %s

                Choose one allowed answer. A yes requires affirmative evidence. When choosing a focus, prefer actionable
                work. Revisit unchanged or blocked work when new evidence or a different next action warrants it.
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

                Start with the existing parent thought. Incorporate child evidence relevant to the parent's purpose.
                %s
                Omit internal thought identifiers.
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
                safeText(child.content()), MEMORY_GUIDANCE);
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
        var input = run.input();
        return input == null ? "(empty)" : asJson(input);
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
