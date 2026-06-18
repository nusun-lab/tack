package us.nusun.llmshare.bean;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.camel.CamelContext;
import org.apache.camel.ServiceStatus;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@Named("resetService")
public class ResetService {
    @Inject
    CamelContext camelContext;

    @Inject
    KafkaTopicProvisioner kafkaTopicProvisioner;

    @Inject
    ProjectMemoryStore projectMemoryStore;

    @ConfigProperty(name = "llm.worker.enabled", defaultValue = "true")
    boolean workerEnabled;

    public Map<String, Object> reset() throws Exception {
        List<String> routesToResume = new ArrayList<>();
        suspendForReset("llm-response-consumer", routesToResume);
        if (workerEnabled) {
            suspendForReset("llm-worker", routesToResume);
        }

        try {
            kafkaTopicProvisioner.resetTopics();
            projectMemoryStore.clear();
        } finally {
            for (String routeId : routesToResume) {
                resumeAfterReset(routeId);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "RESET");
        response.put("topics", kafkaTopicProvisioner.configuredTopicNames());
        response.put("jobsCleared", false);
        response.put("memoryCleared", true);
        response.put("message", "Kafka topics and in-memory workspace view state were reset.");
        return response;
    }

    private void suspendForReset(String routeId, List<String> routesToResume) {
        try {
            ServiceStatus status = camelContext.getRouteController().getRouteStatus(routeId);
            if (status != null && status.isStarted()) {
                camelContext.getRouteController().suspendRoute(routeId);
                routesToResume.add(routeId);
            }
        } catch (Exception ignored) {
        }
    }

    private void resumeAfterReset(String routeId) {
        try {
            ServiceStatus status = camelContext.getRouteController().getRouteStatus(routeId);
            if (status == ServiceStatus.Suspended) {
                camelContext.getRouteController().resumeRoute(routeId);
            }
        } catch (Exception ignored) {
        }
    }
}
