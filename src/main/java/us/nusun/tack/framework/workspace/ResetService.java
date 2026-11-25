package us.nusun.tack.framework.workspace;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.camel.Header;
import us.nusun.tack.framework.run.ProjectionStore;
import us.nusun.tack.framework.sandbox.PersonaSandboxManager;
import us.nusun.tack.framework.run.AgentRunStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@Named("resetService")
public class ResetService {
    @Inject ProjectionStore projections;
    @Inject AgentRunStore agentRuns;
    @Inject KafkaTopicProvisioner kafkaTopics;
    @Inject PersonaSandboxManager sandboxes;

    public Map<String, Object> reset(@Header("requestId") String requestId) throws Exception {
        List<String> purgedTopics = kafkaTopics.purgeConfiguredTopics();
        List<String> destroyedSandboxes = sandboxes.destroyAll();
        projections.clear();
        agentRuns.clear();
        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("status", "RESET");
        if (requestId != null && !requestId.isBlank()) {
            response.put("requestId", requestId);
        }
        response.put("kafkaTopicsPurged", purgedTopics);
        response.put("sandboxesDestroyed", destroyedSandboxes);
        return Map.copyOf(response);
    }
}
