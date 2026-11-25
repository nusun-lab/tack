package us.nusun.tack.framework.run;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

@ApplicationScoped
public class ProjectionStore {
    private final ConcurrentHashMap<String, AgentRunProjection> projections = new ConcurrentHashMap<>();

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
