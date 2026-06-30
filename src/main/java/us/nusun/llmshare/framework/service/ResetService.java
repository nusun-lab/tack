package us.nusun.llmshare.framework.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import us.nusun.llmshare.framework.projection.ProjectionStore;
import us.nusun.llmshare.framework.state.AgentRunStore;

import java.util.Map;

@ApplicationScoped
public class ResetService {
    @Inject ProjectionStore projections;
    @Inject AgentRunStore agentRuns;

    public Map<String, Object> reset() {
        projections.clear();
        if (agentRuns != null) {
            agentRuns.clear();
        }
        return Map.of("status", "RESET");
    }

    public Map<String, Object> resetRequest(String requestId) {
        projections.clear();
        if (agentRuns != null) {
            agentRuns.clear();
        }
        return Map.of("status", "RESET", "requestId", requestId);
    }
}
