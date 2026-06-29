package us.nusun.llmshare.framework.lifecycle;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import us.nusun.llmshare.framework.projection.ProjectionStore;

import java.util.Map;

@ApplicationScoped
public class ResetService {
    @Inject ProjectionStore projections;

    public Map<String, Object> reset() {
        projections.clear();
        return Map.of("status", "RESET");
    }

    public Map<String, Object> resetRequest(String requestId) {
        projections.clear();
        return Map.of("status", "RESET", "requestId", requestId);
    }
}
