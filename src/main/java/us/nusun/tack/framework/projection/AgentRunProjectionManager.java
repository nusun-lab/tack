package us.nusun.tack.framework.projection;

import static us.nusun.tack.framework.model.FrameworkText.firstNonBlank;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import us.nusun.tack.framework.inventory.PersonaCatalog;
import us.nusun.tack.framework.inventory.RoutineCatalog;
import us.nusun.tack.framework.llm.LlmExecutionContext;
import us.nusun.tack.framework.model.AgentRunProjection;
import us.nusun.tack.framework.model.LlmJobEnvelope;
import us.nusun.tack.framework.model.LlmPassType;
import us.nusun.tack.framework.model.Mission;
import us.nusun.tack.framework.model.Persona;
import us.nusun.tack.framework.model.StepResult;
import us.nusun.tack.framework.model.DecisionResult;
import us.nusun.tack.framework.model.CreateResult;
import us.nusun.tack.framework.model.RoutineDefinition;
import us.nusun.tack.framework.model.SubroutineDefinition;
import us.nusun.tack.framework.model.ThoughtFrame;
import us.nusun.tack.framework.routine.ThoughtStackSupport;
import us.nusun.tack.framework.state.AgentRun;
import us.nusun.tack.framework.state.ThoughtStack;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@ApplicationScoped
public class AgentRunProjectionManager {
    private static final int MAX_TIMELINE_ENTRIES = 10;

    @Inject ProjectionStore store;
    @Inject PersonaCatalog personas;
    @Inject RoutineCatalog routines;

    public void record(Exchange exchange, String eventType) {
        record(exchange, eventType, Map.of());
    }

    public void record(Exchange exchange, String eventType, Map<String, Object> details) {
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        if (run == null) {
            return;
        }
        Mission mission = exchange.getProperty("mission", Mission.class);
        ThoughtStack stack = exchange.getProperty(ThoughtStackSupport.STACK_PROPERTY, ThoughtStack.class);
        String profile = exchange.getProperty("toolProfile", String.class);
        String lane = firstNonBlank(exchange.getProperty("resolvedLane", String.class),
                exchange.getProperty("laneId", String.class));
        String failure = exchange.getProperty("latestPrimitiveFailure", String.class);
        record(run, mission, stack, profile, lane, failure,
                new ProjectionEvent(eventType, run.currentPrimitive(), run.currentId(), details));
    }

    public void recordAccepted(Mission mission, AgentRun run) {
        record(run, mission, null, null, null, null,
                new ProjectionEvent("AGENT_RUN_ACCEPTED", null, null, Map.of()));
    }

    private void record(AgentRun run, Mission mission, ThoughtStack stack, String profile, String lane,
                        String failure, ProjectionEvent event) {
        store.update(run.id(), previous -> build(run, mission, stack, profile, lane, failure, previous, event));
    }

    public void recordPrimitiveCompleted(Exchange exchange, String primitive, String primitiveId, String response) {
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        if (run == null) {
            return;
        }
        store.updateExisting(run.id(), previous -> {
            Map<String, Object> current = previous.current();
            if (!Objects.equals(current.get("primitive"), primitive) || !Objects.equals(current.get("id"), primitiveId)) {
                return previous;
            }
            Map<String, Object> completed = new LinkedHashMap<>(current);
            completed.put("response", response == null ? "" : response);
            return copy(previous, Map.copyOf(completed), previous.toolProfile(), previous.laneId(), null);
        });
    }

    public void recordLlmStarted(Exchange exchange) {
        String agentRunId = exchange.getProperty("agentRunId", String.class);
        LlmJobEnvelope job = exchange.getProperty("llmJob", LlmJobEnvelope.class);
        if (agentRunId == null || agentRunId.isBlank() || job == null) {
            return;
        }

        LlmExecutionContext invocation = LlmExecutionContext.on(exchange);
        String primitive = invocation.primitive();
        String primitiveId = invocation.primitiveId();
        ProjectionEvent event = new ProjectionEvent(llmStartedEvent(job.passType(), primitive), primitive, primitiveId,
                metadataMap(job, "thoughtConsolidationDetails"));
        store.updateExisting(agentRunId, previous -> {
            if (!projectsPrimaryRequest(job.passType())) {
                return copy(previous, previous.current(), previous.toolProfile(), previous.laneId(), event);
            }
            LinkedHashMap<String, Object> current = new LinkedHashMap<>();
            if (primitive != null && !primitive.isBlank()) current.put("primitive", primitive);
            if (primitiveId != null && !primitiveId.isBlank()) current.put("id", primitiveId);
            current.put("jobId", job.jobId().toString());
            current.put("request", Objects.toString(invocation.primitivePrompt(), ""));
            current.put("response", "");
            return copy(previous, Map.copyOf(current), firstNonBlank(invocation.toolProfile(), previous.toolProfile()),
                    firstNonBlank(job.laneId(), previous.laneId()), event);
        });
    }

    private static AgentRunProjection copy(AgentRunProjection previous, Map<String, Object> current, String profile,
                                           String lane, ProjectionEvent event) {
        return new AgentRunProjection(previous.agentRunId(), previous.missionId(), previous.parentAgentRunId(),
                previous.status(), previous.mission(), previous.persona(), previous.routine(), previous.subroutine(),
                previous.input(), current, profile, lane, previous.thoughtStack(), thoughtHistory(previous, event),
                previous.latestResult(), timeline(previous, event), Instant.now());
    }

    private static boolean projectsPrimaryRequest(LlmPassType passType) {
        return passType == LlmPassType.ACTION
                || passType == LlmPassType.DECISION
                || passType == LlmPassType.CREATION;
    }

    private static String llmStartedEvent(LlmPassType passType, String primitive) {
        if (passType == LlmPassType.ACTION) return "ACT_STARTED";
        if (passType == LlmPassType.DECISION) return "DECIDE_STARTED";
        if (passType == LlmPassType.CREATION) return "CREATE_STARTED";
        if (passType == LlmPassType.CONSOLIDATION) return "THOUGHT_CONSOLIDATION_STARTED";
        if (passType == LlmPassType.SYNTHESIS && "act".equals(primitive)) return "SYNTHESIS_PASS_STARTED";
        return "LLM_PASS_STARTED";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metadataMap(LlmJobEnvelope job, String key) {
        if (job == null || job.metadata() == null) return Map.of();
        Object value = job.metadata().get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private AgentRunProjection build(AgentRun run, Mission mission, ThoughtStack stack, String profile, String lane,
                                     String failure, AgentRunProjection previous, ProjectionEvent event) {
        Persona persona = personas.getRequired(run.personaId());
        RoutineDefinition routine = routines.getRequired(run.routineId());
        SubroutineDefinition subroutine = run.subroutineId() == null ? null : routine.requireSubroutine(run.subroutineId());
        return new AgentRunProjection(run.id(), run.missionId(), run.parentAgentRunId(), run.status(),
                missionSummary(mission, run.missionId()), personaSummary(persona), routineSummary(routine),
                subroutineSummary(subroutine), run.input(), previous == null ? Map.of() : previous.current(),
                firstNonBlank(previous == null ? null : previous.toolProfile(), profile),
                firstNonBlank(previous == null ? null : previous.laneId(), lane),
                thoughtFrames(stack), thoughtHistory(previous, event), latest(failure, run),
                timeline(previous, event), Instant.now());
    }

    private static Map<String, Object> missionSummary(Mission mission, String missionId) {
        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        if (mission != null) {
            summary.put("id", mission.id());
            summary.put("goal", mission.goal());
            summary.put("status", mission.status());
            summary.put("createdAt", mission.createdAt());
        } else if (missionId != null && !missionId.isBlank()) {
            summary.put("id", missionId);
        }
        return Map.copyOf(summary);
    }

    private static Map<String, Object> personaSummary(Persona persona) {
        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", persona.id());
        summary.put("name", persona.name());
        if (persona.label() != null) summary.put("label", persona.label());
        if (persona.color() != null) summary.put("color", persona.color());
        return summary;
    }

    private static Map<String, Object> routineSummary(RoutineDefinition routine) {
        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", routine.id());
        summary.put("name", routine.name());
        summary.put("description", routine.description());
        summary.put("routeUri", routine.routeUri());
        return summary;
    }

    private static Map<String, Object> subroutineSummary(SubroutineDefinition subroutine) {
        if (subroutine == null) {
            return Map.of();
        }
        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", subroutine.id());
        summary.put("description", subroutine.description());
        summary.put("routeUri", subroutine.routeUri());
        return summary;
    }

    private static Map<String, Object> latest(String failure, AgentRun run) {
        if (failure != null && !failure.isBlank()) {
            return Map.of("type", "failure", "summary", failure);
        }
        return switch (run.latestResult()) {
            case StepResult step -> step.summary() == null
                    ? Map.of("type", "step") : Map.of("type", "step", "summary", step.summary());
            case DecisionResult decision -> Map.of("type", "decision", "result", decision.result(),
                    "reason", decision.reason() == null ? "" : decision.reason());
            case CreateResult creation -> Map.of("type", "create", "createId", creation.createId());
            case null -> Map.of();
        };
    }

    private static List<Map<String, Object>> thoughtFrames(ThoughtStack stack) {
        if (stack == null) {
            return List.of();
        }
        List<ThoughtFrame> frames = stack.frames();
        ArrayList<Map<String, Object>> rows = new ArrayList<>(frames.size());
        for (ThoughtFrame frame : frames) {
            rows.add(thoughtFrame(frame));
        }
        return List.copyOf(rows);
    }

    private static Map<String, Object> thoughtFrame(ThoughtFrame frame) {
        String content = frame.content() == null ? "" : frame.content();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", frame.id());
        if (frame.name() != null && !frame.name().isBlank()) {
            row.put("name", frame.name());
            row.put("displayId", frame.name());
        }
        row.put("context", frame.context());
        row.put("updatedAt", frame.updatedAt());
        row.put("maxTokens", frame.maxTokens());
        row.put("approxTokens", content.isBlank() ? 0 : Math.max(1, content.length() / 4));
        row.put("contentPreview", content.length() <= 240 ? content : content.substring(0, 230) + " [truncated]");
        row.put("content", content);
        row.put("truncated", content.length() > 240);
        return row;
    }

    private static List<Map<String, Object>> timeline(AgentRunProjection previous, ProjectionEvent event) {
        List<Map<String, Object>> history = previous == null ? List.of() : previous.timeline();
        if (event == null) {
            return history;
        }
        var entries = new ArrayList<>(history.subList(Math.max(0, history.size() - MAX_TIMELINE_ENTRIES + 1), history.size()));
        entries.add(event.row(false));
        return List.copyOf(entries);
    }

    private static List<Map<String, Object>> thoughtHistory(AgentRunProjection previous, ProjectionEvent event) {
        List<Map<String, Object>> history = previous == null ? List.of() : previous.thoughtHistory();
        if (event == null || !event.type().startsWith("THOUGHT_")) {
            return history;
        }
        var entries = new ArrayList<>(history);
        entries.add(event.row(true));
        return List.copyOf(entries);
    }

    private record ProjectionEvent(String type, String primitive, String id, Map<String, Object> details, Instant at) {
        ProjectionEvent(String type, String primitive, String id, Map<String, Object> details) {
            this(type, primitive, id, details, Instant.now());
        }

        Map<String, Object> row(boolean includeDetails) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", type);
            row.put("at", at.toString());
            if (primitive != null) row.put("primitive", primitive);
            if (id != null) row.put("stepId", id);
            if (includeDetails && details != null) row.putAll(details);
            return Map.copyOf(row);
        }
    }
}
