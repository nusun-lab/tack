package us.nusun.llmshare.framework.inventory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import us.nusun.llmshare.framework.model.InferenceLaneDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class InferenceLaneRegistry {
    private final Map<String, InferenceLaneDefinition> lanes;

    @Inject
    public InferenceLaneRegistry(Instance<InferenceLaneProvider> laneProviders) {
        this(laneProviders.stream().map(InferenceLaneProvider::inferenceLane).toList());
    }

    public InferenceLaneRegistry(List<InferenceLaneDefinition> lanes) {
        LinkedHashMap<String, InferenceLaneDefinition> laneRows = new LinkedHashMap<>();
        for (InferenceLaneDefinition lane : lanes == null ? List.<InferenceLaneDefinition>of() : lanes) {
            if (laneRows.put(lane.id(), lane) != null) {
                throw new IllegalArgumentException("Duplicate inference lane id: " + lane.id());
            }
        }
        LinkedHashMap<String, String> requestTopicOwners = new LinkedHashMap<>();
        for (InferenceLaneDefinition lane : laneRows.values()) {
            String previousLaneId = requestTopicOwners.putIfAbsent(lane.requestTopic(), lane.id());
            if (previousLaneId != null) {
                throw new IllegalArgumentException("Duplicate inference lane request topic '" + lane.requestTopic()
                        + "' used by lanes '" + previousLaneId + "' and '" + lane.id()
                        + "'. Each inference lane must use a unique request topic to avoid duplicate LLM execution.");
            }
        }
        this.lanes = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(laneRows));
    }

    public InferenceLaneDefinition getRequired(String id) {
        InferenceLaneDefinition lane = lanes.get(id);
        if (lane == null) {
            throw new IllegalArgumentException("Unknown inference lane: " + id);
        }
        return lane;
    }

    public List<InferenceLaneDefinition> allLanes() {
        return lanes.values().stream().toList();
    }
}
