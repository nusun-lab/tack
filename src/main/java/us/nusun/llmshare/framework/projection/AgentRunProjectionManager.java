package us.nusun.llmshare.framework.projection;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import us.nusun.llmshare.framework.inventory.PersonaCatalog;
import us.nusun.llmshare.framework.inventory.RoutineCatalog;
import us.nusun.llmshare.framework.model.AgentRunProjection;
import us.nusun.llmshare.framework.state.AgentRun;
import us.nusun.llmshare.framework.model.Persona;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.model.ThoughtFrame;
import us.nusun.llmshare.framework.inventory.ExecutionRuntimeRegistry;
import us.nusun.llmshare.framework.state.ThoughtStack;
import us.nusun.llmshare.framework.routine.ThoughtStackSupport;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class AgentRunProjectionManager {
    @Inject ProjectionStore store;
    @Inject PersonaCatalog personas;
    @Inject RoutineCatalog routines;
    @Inject ExecutionRuntimeRegistry runtimes;

    public void record(Exchange exchange, String eventType) {
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        if (run == null) {
            return;
        }
        ProjectionTimeline timeline = timeline(exchange);
        timeline.add(eventType);
        store.put(build(exchange, run, timeline.entries()));
    }

    public AgentRunProjection build(Exchange exchange, AgentRun run, List<Map<String, Object>> timeline) {
        Persona persona = personas.getRequired(run.personaId());
        RoutineDefinition routine = routines.getRequired(run.routineId());
        ThoughtStack stack = exchange.getProperty(ThoughtStackSupport.STACK_PROPERTY, ThoughtStack.class);
        String toolRuntime = exchange.getProperty("resolvedToolRuntime", String.class);
        String synthesisRuntime = exchange.getProperty("resolvedSynthesisRuntime", String.class);
        return new AgentRunProjection(
                run.id(),
                run.missionId(),
                run.parentAgentRunId(),
                run.status(),
                Map.of("id", persona.id(), "name", persona.name()),
                Map.of("id", routine.id(), "name", routine.name()),
                current(run, exchange),
                exchange.getProperty("toolProfile", String.class),
                compactMap("toolRuntime", toolRuntime, "synthesisRuntime", synthesisRuntime),
                compactMap("toolLane", lane(toolRuntime), "synthesisLane", lane(synthesisRuntime)),
                thoughtFrames(stack),
                latest(exchange, run),
                timeline == null ? List.of() : timeline,
                Instant.now());
    }

    private String lane(String runtimeId) {
        if (runtimeId == null || runtimeId.isBlank()) {
            return null;
        }
        return runtimes.getRequired(runtimeId).laneId();
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
            return Map.of("type", "step", "status", run.latestStepResult().status(), "summary", run.latestStepResult().summary());
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
        return stack.frames().stream().map(AgentRunProjectionManager::thoughtFrame).toList();
    }

    private static Map<String, Object> thoughtFrame(ThoughtFrame frame) {
        String content = frame.content() == null ? "" : frame.content();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", frame.id());
        row.put("context", frame.context());
        row.put("maxTokens", frame.maxTokens());
        row.put("approxTokens", Math.max(1, content.length() / 4));
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

    private static Map<String, String> compactMap(String k1, String v1, String k2, String v2) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        if (v1 != null) result.put(k1, v1);
        if (v2 != null) result.put(k2, v2);
        return result;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.length() <= 180 ? value : value.substring(0, 177) + "...";
            }
        }
        return null;
    }

    static class ProjectionTimeline {
        private final ArrayList<Map<String, Object>> entries = new ArrayList<>();
        ProjectionTimeline() {
        }
        ProjectionTimeline(List<Map<String, Object>> existing) {
            if (existing != null) {
                entries.addAll(existing);
            }
        }
        void add(String type) {
            entries.add(Map.of("type", type, "at", Instant.now().toString()));
        }
        List<Map<String, Object>> entries() {
            return List.copyOf(entries);
        }
    }
}
