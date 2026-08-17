package us.nusun.llmshare.framework.tack;

import static us.nusun.llmshare.framework.model.FrameworkText.firstNonBlank;
import static us.nusun.llmshare.framework.model.FrameworkText.putIfNonBlank;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import us.nusun.llmshare.framework.inventory.InferenceLaneRegistry;
import us.nusun.llmshare.framework.inventory.PersonaCatalog;
import us.nusun.llmshare.framework.inventory.RoutineCatalog;
import us.nusun.llmshare.framework.inventory.ToolProfileRegistry;
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
import us.nusun.llmshare.framework.route.TerminalToolRoute;
import us.nusun.llmshare.framework.routine.ThoughtStackSupport;
import us.nusun.llmshare.framework.service.MissionRosterService;
import us.nusun.llmshare.framework.state.AgentRun;
import us.nusun.llmshare.framework.state.ThoughtStack;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ActExecutor {
    @Inject ObjectMapper objectMapper;
    @Inject ThoughtStackSupport thoughts;
    @Inject PersonaCatalog personas;
    @Inject RoutineCatalog routines;
    @Inject ToolProfileRegistry toolProfiles;
    @Inject MissionRosterService missionRoster;
    @Inject InferenceLaneRegistry lanes;
    @Inject AgentRunProjectionManager projections;

    public record Request(String id, String prompt, String toolProfile, String lane) {
    }

    private record Completion(String summary, String content) {
    }

    public void execute(Exchange exchange, Request request, ProducerTemplate llm) {
        AgentRun run = currentRun(exchange);
        ThoughtStack stack = thoughts.stack(exchange);
        ThoughtFrame leaf = stack.leaf();
        if (leaf == null) {
            throw new IllegalStateException("Cannot run tack:act because no active thought is present");
        }

        String laneId = resolveLane(exchange, request.lane());
        ToolProfile profile = toolProfiles.getRequired(firstNonBlank(request.toolProfile(), ToolProfileRegistry.NONE_ID));
        run.currentPrimitive("act");
        run.currentId(request.id());
        exchange.setProperty("personaId", run.personaId());
        exchange.setProperty("toolProfile", profile.id());
        exchange.setProperty("laneId", laneId);
        exchange.setProperty("resolvedLane", laneId);
        exchange.removeProperty("latestPrimitiveFailure");
        projections.record(exchange, "ACT_STARTED");

        LlmJobRequest job = new LlmJobRequest(
                null,
                laneId,
                LlmPassType.TOOL,
                "act:" + request.id(),
                personas.getRequired(run.personaId()).name(),
                0,
                prompt(exchange, request, profile, leaf),
                systemPrompt(run.personaId()),
                null,
                metadata(exchange, laneId, profile.id()),
                null);

        projections.record(exchange, "AGENT_INVOCATION_QUEUED");
        LlmJobResult llmResult = call(exchange, llm, job);
        projections.record(exchange, "AGENT_INVOCATION_COMPLETED");
        Completion completion = parseCompletion(llmResult.content());

        ThoughtFrame rewritten = stack.rewrite(leaf.id(), completion.content(), Instant.now());
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        details.put("thoughtName", rewritten.name());
        details.put("thoughtId", rewritten.id());
        details.put("content", rewritten.content());
        details.put("primitive", "act");
        details.put("cause", request.id());
        details.put("timestamp", Instant.now().toString());
        projections.record(exchange, "THOUGHT_REWRITTEN", details);

        StepResult result = new StepResult(request.id(), completion.summary(), List.of());
        run.apply(result);
        exchange.setProperty("latestStepResult", result);
        exchange.getMessage().setBody(result);
        projections.record(exchange, "ACT_COMPLETED");
    }

    private LlmJobResult call(Exchange exchange, ProducerTemplate llm, LlmJobRequest request) {
        try {
            exchange.removeProperty("llmJobId");
            exchange.removeProperty("llmJob");
            exchange.removeProperty("llmResult");
            exchange.removeProperty("llmQueueFailureHandling");
            exchange.getMessage().setBody(request);
            Exchange routed = llm.send("direct:llm", exchange);
            if (routed.getException() != null) throw routed.getException();
            LlmJobResult result = routed.getMessage().getBody(LlmJobResult.class);
            if (result == null) throw new IllegalStateException("Agent invocation returned no result");
            if (result.status() != LlmJobStatus.SUCCEEDED) {
                throw new IllegalStateException("Agent invocation failed: " + firstNonBlank(result.error(), "unknown error"));
            }
            if (result.content() == null || result.content().isBlank()) {
                throw new IllegalStateException("Agent invocation returned empty content");
            }
            return result;
        } catch (Exception e) {
            exchange.setProperty("latestPrimitiveFailure", firstNonBlank(e.getMessage(), e.getClass().getSimpleName()));
            projections.record(exchange, "LLM_PASS_FAILED");
            if (e instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Agent invocation failed: " + e.getMessage(), e);
        }
    }

    private Completion parseCompletion(String content) {
        try {
            Completion completion = objectMapper.readValue(content, Completion.class);
            if (completion.summary() == null || completion.summary().isBlank()) {
                throw new IllegalArgumentException("finish_act returned blank summary");
            }
            if (completion.content() == null || completion.content().isBlank()) {
                throw new IllegalArgumentException("finish_act returned blank thought content");
            }
            return completion;
        } catch (Exception e) {
            throw new IllegalStateException("Agent did not return the finish_act JSON result: " + e.getMessage(), e);
        }
    }

    private String resolveLane(Exchange exchange, String requested) {
        RoutineDefinition routine = routines.getRequired(currentRun(exchange).routineId());
        String laneId = firstNonBlank(requested, routine.defaultLane(), RoutineDefinition.FRAMEWORK_DEFAULT_LANE);
        lanes.getRequired(laneId);
        return laneId;
    }

    private Map<String, Object> metadata(Exchange exchange, String laneId, String profileId) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        AgentRun run = currentRun(exchange);
        Mission mission = exchange.getProperty("mission", Mission.class);
        putIfNonBlank(metadata, "agentRunId", run.id());
        putIfNonBlank(metadata, "personaId", run.personaId());
        putIfNonBlank(metadata, "missionId", mission == null ? null : mission.id());
        putIfNonBlank(metadata, "primitive", "act");
        putIfNonBlank(metadata, "primitiveId", run.currentId());
        putIfNonBlank(metadata, "toolProfile", profileId);
        putIfNonBlank(metadata, "laneId", laneId);
        putIfNonBlank(metadata, "passType", LlmPassType.TOOL.name());
        metadata.put("camelToolTags", TerminalToolRoute.TAG);
        return Map.copyOf(metadata);
    }

    private String systemPrompt(String personaId) {
        Persona persona = personas.getRequired(personaId);
        return """
                You are %s. %s
                Traits: %s

                Complete exactly one ACT using the available tools.
                Use the smallest useful number of tool calls and only the configured tool profile.
                When the act is complete, call finish_act exactly once.
                finish_act.summary is the compact externally useful result.
                finish_act.content is the complete replacement content for the current durable thought.
                After finish_act returns, return exactly that same JSON object with no prose.
                """.formatted(
                text(persona.name()),
                text(persona.description()),
                persona.traits().isEmpty() ? "(none)" : String.join("; ", persona.traits())).strip();
    }

    private String prompt(Exchange exchange, Request request, ToolProfile profile, ThoughtFrame leaf) {
        Mission mission = exchange.getProperty("mission", Mission.class);
        AgentRun run = currentRun(exchange);
        List<ThoughtFrame> frames = thoughts.stack(exchange).frames();
        String ancestors = frames.size() <= 1 ? "(none)" : frames.subList(0, frames.size() - 1).stream()
                .map(frame -> frame.name() + ": " + text(frame.content()))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("(none)");
        return """
                Mission: %s
                Agent-run input: %s
                Mission roster:
                %s

                Current thought purpose: %s
                Current thought content: %s
                Ancestor context:
                %s

                Tool profile: %s
                Tool guidance: %s

                ACT: %s
                """.formatted(
                mission == null ? "(none)" : text(mission.goal()),
                json(run.workingState().get("input")),
                missionRoster.missionRosterPrompt(exchange),
                text(leaf.context()),
                text(leaf.content()),
                ancestors,
                profile.id(),
                text(profile.promptGuidance()),
                text(request.prompt())).strip();
    }

    private String json(Object value) {
        if (value == null) return "null";
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private AgentRun currentRun(Exchange exchange) {
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        if (run == null) throw new IllegalStateException("No AgentRun on exchange");
        return run;
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? "(none)" : value.strip();
    }
}
