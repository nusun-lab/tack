package us.nusun.tack.framework.projection;

import jakarta.enterprise.context.ApplicationScoped;
import us.nusun.tack.framework.model.AgentRunProjection;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

@ApplicationScoped
public class ProjectionStore {
    private final ConcurrentHashMap<String, AgentRunProjection> projections = new ConcurrentHashMap<>();

    public void put(AgentRunProjection projection) {
        projections.put(projection.agentRunId(), projection);
    }

    void update(String id, UnaryOperator<AgentRunProjection> change) {
        projections.compute(id, (key, current) -> change.apply(current));
    }

    void updateExisting(String id, UnaryOperator<AgentRunProjection> change) {
        projections.computeIfPresent(id, (key, current) -> change.apply(current));
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
