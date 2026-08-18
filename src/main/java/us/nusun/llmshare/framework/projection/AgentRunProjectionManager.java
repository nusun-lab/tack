package us.nusun.llmshare.framework.projection;

import static us.nusun.llmshare.framework.model.FrameworkText.firstNonBlank;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import us.nusun.llmshare.framework.inventory.PersonaCatalog;
import us.nusun.llmshare.framework.inventory.RoutineCatalog;
import us.nusun.llmshare.framework.model.AgentRunProjection;
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
        ProjectionTimeline timeline = timeline(exchange);
        timeline.add(eventType, run);
        ThoughtHistory history = thoughtHistory(exchange);
        if (eventType != null && eventType.startsWith("THOUGHT_")) {
            history.add(eventType, details, run);
        }
        store.put(build(exchange, run, timeline.entries(), history.entries()));
    }

    public AgentRunProjection build(Exchange exchange, AgentRun run, List<Map<String, Object>> timeline) {
        return build(exchange, run, timeline, thoughtHistory(exchange).entries());
    }

    public AgentRunProjection build(Exchange exchange, AgentRun run, List<Map<String, Object>> timeline, List<Map<String, Object>> thoughtHistory) {
        Mission mission = exchange.getProperty("mission", Mission.class);
        Persona persona = personas.getRequired(run.personaId());
        RoutineDefinition routine = routines.getRequired(run.routineId());
        SubroutineDefinition subroutine = run.subroutineId() == null ? null : routine.requireSubroutine(run.subroutineId());
        ThoughtStack stack = exchange.getProperty(ThoughtStackSupport.STACK_PROPERTY, ThoughtStack.class);
        String laneId = exchange.getProperty("laneId", String.class);
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
        current.put("promptSummary", exchange.getProperty("primitivePrompt", String.class));
        return current;
    }

    private static Map<String, Object> latest(Exchange exchange, AgentRun run) {
        String failure = exchange.getProperty("latestPrimitiveFailure", String.class);
        if (failure != null && !failure.isBlank()) {
            return Map.of("type", "failure", "summary", failure);
        }
        if (run.latestStepResult() != null) {
            return Map.of("type", "step", "summary", run.latestStepResult().summary());
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
        if (timeline == null) {
            AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
            AgentRunProjection current = run == null ? null : store.get(run.id());
            timeline = new ProjectionTimeline(current == null ? List.of() : current.timeline());
            exchange.setProperty("projectionTimeline", timeline);
        }
        return timeline;
    }

    private ThoughtHistory thoughtHistory(Exchange exchange) {
        ThoughtHistory history = exchange.getProperty("thoughtHistory", ThoughtHistory.class);
        if (history == null) {
            AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
            AgentRunProjection current = run == null ? null : store.get(run.id());
            history = new ThoughtHistory(current == null ? List.of() : current.thoughtHistory());
            exchange.setProperty("thoughtHistory", history);
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
            LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", type);
            entry.put("at", Instant.now().toString());
            if (run != null && run.currentPrimitive() != null) {
                entry.put("primitive", run.currentPrimitive());
            }
            if (run != null && run.currentId() != null) {
                entry.put("stepId", run.currentId());
            }
            entries.add(Map.copyOf(entry));
            trim();
        }

        List<Map<String, Object>> entries() {
            return List.copyOf(entries);
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
            LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", type);
            entry.put("at", Instant.now().toString());
            if (run != null && run.currentPrimitive() != null) {
                entry.put("primitive", run.currentPrimitive());
            }
            if (run != null && run.currentId() != null) {
                entry.put("stepId", run.currentId());
            }
            if (details != null) {
                entry.putAll(details);
            }
            entries.add(Map.copyOf(entry));
        }

        List<Map<String, Object>> entries() {
            return List.copyOf(entries);
        }
    }
}
