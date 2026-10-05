package us.nusun.llmshare.framework.projection;

import static us.nusun.llmshare.framework.model.FrameworkText.firstNonBlank;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import us.nusun.llmshare.framework.inventory.PersonaCatalog;
import us.nusun.llmshare.framework.inventory.RoutineCatalog;
import us.nusun.llmshare.framework.model.AgentRunProjection;
import us.nusun.llmshare.framework.model.LlmJobEnvelope;
import us.nusun.llmshare.framework.model.LlmPassType;
import us.nusun.llmshare.framework.model.Mission;
import us.nusun.llmshare.framework.model.Persona;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.model.SubroutineDefinition;
import us.nusun.llmshare.framework.model.ThoughtFrame;
import us.nusun.llmshare.framework.routine.ThoughtStackSupport;
import us.nusun.llmshare.framework.state.AgentRun;
import us.nusun.llmshare.framework.state.ThoughtStack;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        AgentRunProjection existing = store.get(run.id());
        ProjectionTimeline timeline = timeline(exchange);
        timeline.add(eventType, run);
        ThoughtHistory history = thoughtHistory(exchange);
        if (eventType != null && eventType.startsWith("THOUGHT_")) {
            history.add(eventType, details, run);
        }
        AgentRunProjection updated = build(exchange, run, timeline.entries(), history.entries());
        updated = withCurrent(updated, existing == null ? Map.of() : existing.current());
        store.put(updated);
    }

    public AgentRunProjection build(Exchange exchange, AgentRun run, List<Map<String, Object>> timeline) {
        return build(exchange, run, timeline, thoughtHistory(exchange).entries());
    }

    public void recordPrimitiveCompleted(
            Exchange exchange,
            String primitive,
            String primitiveId,
            String response) {
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        if (run == null) {
            return;
        }
        AgentRunProjection currentProjection = store.get(run.id());
        if (currentProjection == null) {
            return;
        }
        Map<String, Object> current = currentProjection.current();
        if (!String.valueOf(current.get("primitive")).equals(primitive)
                || !String.valueOf(current.get("id")).equals(primitiveId)) {
            return;
        }

        LinkedHashMap<String, Object> completed = new LinkedHashMap<>(current);
        completed.put("response", response == null ? "" : response);
        store.put(withCurrent(currentProjection, Map.copyOf(completed), Instant.now()));
    }

    private static AgentRunProjection withCurrent(
            AgentRunProjection projection,
            Map<String, Object> current) {
        return withCurrent(projection, current, projection.updatedAt());
    }

    private static AgentRunProjection withCurrent(
            AgentRunProjection projection,
            Map<String, Object> current,
            Instant updatedAt) {
        return new AgentRunProjection(
                projection.agentRunId(),
                projection.missionId(),
                projection.parentAgentRunId(),
                projection.status(),
                projection.mission(),
                projection.persona(),
                projection.routine(),
                projection.subroutine(),
                projection.input(),
                current == null ? Map.of() : current,
                projection.toolProfile(),
                projection.laneId(),
                projection.thoughtStack(),
                projection.thoughtHistory(),
                projection.latestResult(),
                projection.timeline(),
                updatedAt);
    }

    public void recordLlmStarted(Exchange exchange) {
        String agentRunId = exchange.getProperty("agentRunId", String.class);
        LlmJobEnvelope job = exchange.getProperty("llmJob", LlmJobEnvelope.class);
        if (agentRunId == null || agentRunId.isBlank() || job == null) {
            return;
        }
        AgentRunProjection currentProjection = store.get(agentRunId);
        if (currentProjection == null) {
            return;
        }

        String primitive = metadata(job, "primitive");
        String primitiveId = metadata(job, "primitiveId");

        ProjectionTimeline timeline = new ProjectionTimeline(currentProjection.timeline());
        String startedEvent = llmStartedEvent(job.passType(), primitive);
        timeline.add(startedEvent, primitive, primitiveId);

        ThoughtHistory history = new ThoughtHistory(currentProjection.thoughtHistory());
        if (job.passType() == LlmPassType.CONSOLIDATION) {
            history.add(startedEvent, metadataMap(job, "thoughtConsolidationDetails"), primitive, primitiveId);
        }

        if (!projectsPrimaryRequest(job.passType())) {
            store.put(new AgentRunProjection(
                    currentProjection.agentRunId(),
                    currentProjection.missionId(),
                    currentProjection.parentAgentRunId(),
                    currentProjection.status(),
                    currentProjection.mission(),
                    currentProjection.persona(),
                    currentProjection.routine(),
                    currentProjection.subroutine(),
                    currentProjection.input(),
                    currentProjection.current(),
                    currentProjection.toolProfile(),
                    currentProjection.laneId(),
                    currentProjection.thoughtStack(),
                    history.entries(),
                    currentProjection.latestResult(),
                    timeline.entries(),
                    Instant.now()));
            return;
        }

        LinkedHashMap<String, Object> current = new LinkedHashMap<>();
        if (primitive != null && !primitive.isBlank()) current.put("primitive", primitive);
        if (primitiveId != null && !primitiveId.isBlank()) current.put("id", primitiveId);
        current.put("jobId", job.jobId().toString());
        current.put("request", firstNonBlank(metadata(job, "primitivePrompt"), ""));
        current.put("response", "");

        store.put(new AgentRunProjection(
                currentProjection.agentRunId(),
                currentProjection.missionId(),
                currentProjection.parentAgentRunId(),
                currentProjection.status(),
                currentProjection.mission(),
                currentProjection.persona(),
                currentProjection.routine(),
                currentProjection.subroutine(),
                currentProjection.input(),
                Map.copyOf(current),
                firstNonBlank(metadata(job, "toolProfile"), currentProjection.toolProfile()),
                firstNonBlank(job.laneId(), currentProjection.laneId()),
                currentProjection.thoughtStack(),
                history.entries(),
                currentProjection.latestResult(),
                timeline.entries(),
                Instant.now()));
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

    private static String metadata(LlmJobEnvelope job, String key) {
        if (job == null || job.metadata() == null) return null;
        Object value = job.metadata().get(key);
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metadataMap(LlmJobEnvelope job, String key) {
        if (job == null || job.metadata() == null) return Map.of();
        Object value = job.metadata().get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    public AgentRunProjection build(Exchange exchange, AgentRun run, List<Map<String, Object>> timeline, List<Map<String, Object>> thoughtHistory) {
        Mission mission = exchange.getProperty("mission", Mission.class);
        Persona persona = personas.getRequired(run.personaId());
        RoutineDefinition routine = routines.getRequired(run.routineId());
        SubroutineDefinition subroutine = run.subroutineId() == null ? null : routine.requireSubroutine(run.subroutineId());
        ThoughtStack stack = exchange.getProperty(ThoughtStackSupport.STACK_PROPERTY, ThoughtStack.class);
        String laneId = exchange.getProperty("resolvedLane", String.class);
        if (laneId == null || laneId.isBlank()) {
            laneId = exchange.getProperty("laneId", String.class);
        }
        return new AgentRunProjection(
                run.id(),
                run.missionId(),
                run.parentAgentRunId(),
                run.status(),
                missionSummary(mission, run.missionId()),
                personaSummary(persona),
                routineSummary(routine),
                subroutineSummary(subroutine),
                run.input(),
                current(run, exchange),
                exchange.getProperty("toolProfile", String.class),
                laneId,
                thoughtFrames(stack),
                thoughtHistory == null ? List.of() : thoughtHistory,
                latest(exchange, run),
                timeline == null ? List.of() : timeline,
                Instant.now());
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

    private static Map<String, Object> current(AgentRun run, Exchange exchange) {
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("primitive", run.currentPrimitive());
        current.put("id", run.currentId());
        current.put("promptSummary", firstNonBlank(
                exchange.getProperty("actPrompt", String.class),
                exchange.getProperty("decisionPrompt", String.class),
                exchange.getProperty("createPrompt", String.class)));
        return current;
    }

    private static Map<String, Object> latest(Exchange exchange, AgentRun run) {
        String failure = exchange.getProperty("latestPrimitiveFailure", String.class);
        if (failure != null && !failure.isBlank()) {
            return Map.of("type", "failure", "summary", failure);
        }
        if (run.latestStepResult() != null) {
            LinkedHashMap<String, Object> latest = new LinkedHashMap<>();
            latest.put("type", "step");
            if (run.latestStepResult().summary() != null) {
                latest.put("summary", run.latestStepResult().summary());
            }
            return Map.copyOf(latest);
        }
        if (run.latestDecisionResult() != null) {
            return Map.of("type", "decision", "result", run.latestDecisionResult().result(), "reason", run.latestDecisionResult().reason());
        }
        if (run.latestCreateResult() != null) {
            return Map.of("type", "create", "createId", run.latestCreateResult().createId());
        }
        return Map.of();
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

    private ProjectionTimeline timeline(Exchange exchange) {
        ProjectionTimeline timeline = exchange.getProperty("projectionTimeline", ProjectionTimeline.class);
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        AgentRunProjection current = run == null ? null : store.get(run.id());
        if (timeline == null) {
            timeline = new ProjectionTimeline(current == null ? List.of() : current.timeline());
            exchange.setProperty("projectionTimeline", timeline);
        } else if (current != null) {
            timeline.merge(current.timeline());
        }
        return timeline;
    }

    private ThoughtHistory thoughtHistory(Exchange exchange) {
        ThoughtHistory history = exchange.getProperty("thoughtHistory", ThoughtHistory.class);
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        AgentRunProjection current = run == null ? null : store.get(run.id());
        if (history == null) {
            history = new ThoughtHistory(current == null ? List.of() : current.thoughtHistory());
            exchange.setProperty("thoughtHistory", history);
        } else if (current != null) {
            history.merge(current.thoughtHistory());
        }
        return history;
    }

    static class ProjectionTimeline {
        private final ArrayList<Map<String, Object>> entries = new ArrayList<>();

        ProjectionTimeline() {
        }

        ProjectionTimeline(List<Map<String, Object>> existing) {
            if (existing != null) {
                entries.addAll(existing);
                trim();
            }
        }

        void add(String type, AgentRun run) {
            add(type,
                    run == null ? null : run.currentPrimitive(),
                    run == null ? null : run.currentId());
        }

        void add(String type, String primitive, String stepId) {
            LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", type);
            entry.put("at", Instant.now().toString());
            if (primitive != null) {
                entry.put("primitive", primitive);
            }
            if (stepId != null) {
                entry.put("stepId", stepId);
            }
            entries.add(Map.copyOf(entry));
            trim();
        }

        List<Map<String, Object>> entries() {
            return List.copyOf(entries);
        }

        void merge(List<Map<String, Object>> current) {
            if (current == null) return;
            for (Map<String, Object> entry : current) {
                if (!entries.contains(entry)) entries.add(entry);
            }
            trim();
        }

        private void trim() {
            while (entries.size() > MAX_TIMELINE_ENTRIES) {
                entries.remove(0);
            }
        }
    }

    static class ThoughtHistory {
        private final ArrayList<Map<String, Object>> entries = new ArrayList<>();

        ThoughtHistory(List<Map<String, Object>> existing) {
            if (existing != null) {
                entries.addAll(existing);
            }
        }

        void add(String type, Map<String, Object> details, AgentRun run) {
            add(
                    type,
                    details,
                    run == null ? null : run.currentPrimitive(),
                    run == null ? null : run.currentId());
        }

        void add(String type, Map<String, Object> details, String primitive, String stepId) {
            LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", type);
            entry.put("at", Instant.now().toString());
            if (primitive != null) {
                entry.put("primitive", primitive);
            }
            if (stepId != null) {
                entry.put("stepId", stepId);
            }
            if (details != null) {
                entry.putAll(details);
            }
            entries.add(Map.copyOf(entry));
        }

        void merge(List<Map<String, Object>> current) {
            if (current == null) return;
            for (Map<String, Object> entry : current) {
                if (!entries.contains(entry)) entries.add(entry);
            }
        }

        List<Map<String, Object>> entries() {
            return List.copyOf(entries);
        }
    }
}
