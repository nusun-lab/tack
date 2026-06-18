package us.nusun.llmshare.bean;

import jakarta.enterprise.context.ApplicationScoped;
import us.nusun.llmshare.model.RoutineToolTraceEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@ApplicationScoped
public class RoutineToolTraceTracker {
    private final ConcurrentMap<UUID, List<RoutineToolTraceEntry>> tracesByJobId = new ConcurrentHashMap<>();
    private final ThreadLocal<UUID> currentJobId = new ThreadLocal<>();

    public void prepare(UUID jobId) {
        tracesByJobId.put(jobId, new ArrayList<>());
    }

    public void bind(UUID jobId) {
        currentJobId.set(jobId);
        tracesByJobId.computeIfAbsent(jobId, ignored -> new ArrayList<>());
    }

    public void unbind() {
        currentJobId.remove();
    }

    public void record(String service, String toolName, String arguments, boolean error, String result) {
        UUID jobId = currentJobId.get();
        if (jobId == null) {
            return;
        }

        tracesByJobId.computeIfAbsent(jobId, ignored -> new ArrayList<>())
                .add(new RoutineToolTraceEntry(service, toolName, arguments, error, result));
    }

    public List<RoutineToolTraceEntry> snapshot(UUID jobId) {
        List<RoutineToolTraceEntry> trace = tracesByJobId.get(jobId);
        if (trace == null) {
            return List.of();
        }
        return List.copyOf(trace);
    }

    public void clear(UUID jobId) {
        tracesByJobId.remove(jobId);
    }
}
