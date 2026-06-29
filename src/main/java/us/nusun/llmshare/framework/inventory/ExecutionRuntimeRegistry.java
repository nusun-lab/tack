package us.nusun.llmshare.framework.inventory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import us.nusun.llmshare.framework.model.ExecutionRuntimeDefinition;
import us.nusun.llmshare.framework.model.InferenceLaneDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ExecutionRuntimeRegistry {
    private final Map<String, InferenceLaneDefinition> lanes;
    private final Map<String, ExecutionRuntimeDefinition> runtimes;

    @Inject
    public ExecutionRuntimeRegistry(
            Instance<InferenceLaneProvider> laneProviders,
            Instance<ExecutionRuntimeProvider> runtimeProviders,
            Instance<RuntimeTopologyProvider> topologyProviders) {
        this(
                java.util.stream.Stream.concat(
                        laneProviders.stream().map(InferenceLaneProvider::inferenceLane),
                        topologyProviders.stream().flatMap(provider -> provider.inferenceLanes().stream())).toList(),
                java.util.stream.Stream.concat(
                        runtimeProviders.stream().map(ExecutionRuntimeProvider::executionRuntime),
                        topologyProviders.stream().flatMap(provider -> provider.executionRuntimes().stream())).toList());
    }

    public ExecutionRuntimeRegistry(List<InferenceLaneDefinition> lanes, List<ExecutionRuntimeDefinition> runtimes) {
        LinkedHashMap<String, InferenceLaneDefinition> laneRows = new LinkedHashMap<>();
        for (InferenceLaneDefinition lane : lanes) {
            if (laneRows.put(lane.id(), lane) != null) {
                throw new IllegalArgumentException("Duplicate inference lane id: " + lane.id());
            }
        }
        LinkedHashMap<String, ExecutionRuntimeDefinition> runtimeRows = new LinkedHashMap<>();
        for (ExecutionRuntimeDefinition runtime : runtimes) {
            if (!laneRows.containsKey(runtime.laneId())) {
                throw new IllegalArgumentException("Runtime " + runtime.id() + " references unknown lane " + runtime.laneId());
            }
            if (runtimeRows.put(runtime.id(), runtime) != null) {
                throw new IllegalArgumentException("Duplicate execution runtime id: " + runtime.id());
            }
        }
        this.lanes = Map.copyOf(laneRows);
        this.runtimes = Map.copyOf(runtimeRows);
    }

    public ExecutionRuntimeDefinition getRequired(String id) {
        ExecutionRuntimeDefinition runtime = runtimes.get(id);
        if (runtime == null) {
            throw new IllegalArgumentException("Unknown execution runtime: " + id);
        }
        return runtime;
    }

    public InferenceLaneDefinition laneRequired(String id) {
        InferenceLaneDefinition lane = lanes.get(id);
        if (lane == null) {
            throw new IllegalArgumentException("Unknown inference lane: " + id);
        }
        return lane;
    }

    public List<ExecutionRuntimeDefinition> allRuntimes() {
        return runtimes.values().stream().toList();
    }

    public List<InferenceLaneDefinition> allLanes() {
        return lanes.values().stream().toList();
    }
}
