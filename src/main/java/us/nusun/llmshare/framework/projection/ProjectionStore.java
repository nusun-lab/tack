package us.nusun.llmshare.framework.projection;

import jakarta.enterprise.context.ApplicationScoped;
import us.nusun.llmshare.framework.model.AgentRunProjection;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ProjectionStore {
    private final ConcurrentHashMap<String, AgentRunProjection> projections = new ConcurrentHashMap<>();

    public void put(AgentRunProjection projection) {
        projections.put(projection.agentRunId(), projection);
    }

    public AgentRunProjection get(String agentRunId) {
        return projections.get(agentRunId);
    }

    public List<AgentRunProjection> all() {
        return projections.values().stream()
                .sorted((left, right) -> right.updatedAt().compareTo(left.updatedAt()))
                .toList();
    }

    public void clear() {
        projections.clear();
    }
}
