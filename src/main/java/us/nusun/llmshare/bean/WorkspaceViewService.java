package us.nusun.llmshare.bean;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.camel.CamelContext;
import org.apache.camel.ServiceStatus;
import us.nusun.llmshare.bean.KafkaTopicProvisioner;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@Named("workspaceViewService")
public class WorkspaceViewService {
    @Inject
    ProjectMemoryStore projectMemoryStore;

    @Inject
    CamelContext camelContext;

    @Inject
    KafkaTopicProvisioner kafkaTopicProvisioner;

    public Map<String, Object> view() {
        List<Map<String, Object>> routes = routeStatuses();
        List<Map<String, Object>> projects = projectViews();
        List<Map<String, Object>> scratchpads = scratchpadViews();
        List<Map<String, Object>> topicDetails = kafkaTopicProvisioner.topicDetails();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("timestamp", Instant.now().toString());
        response.put("topics", kafkaTopicProvisioner.configuredTopicNames());
        response.put("routes", routes);
        response.put("projects", projects);
        response.put("scratchpads", scratchpads);
        response.put("topicDetails", topicDetails);
        response.put("summary", summary(routes, topicDetails, projects, scratchpads));
        return response;
    }

    private List<Map<String, Object>> projectViews() {
        return projectMemoryStore.snapshotProjectBasics().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("requestId", entry.getKey());
                    row.put("projectBasics", entry.getValue());
                    row.put("length", entry.getValue() == null ? 0 : entry.getValue().length());
                    row.put("preview", preview(entry.getValue()));
                    row.put("fullText", entry.getValue());
                    return row;
                })
                .toList();
    }

    private List<Map<String, Object>> scratchpadViews() {
        return projectMemoryStore.snapshotScratchpads().entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(entry -> {
                    String key = entry.getKey();
                    int split = key.indexOf("::");
                    String requestId = split >= 0 ? key.substring(0, split) : key;
                    String roleId = split >= 0 ? key.substring(split + 2) : "";
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("requestId", requestId);
                    row.put("roleId", roleId);
                    row.put("scratchpad", entry.getValue());
                    row.put("length", entry.getValue() == null ? 0 : entry.getValue().length());
                    row.put("preview", preview(entry.getValue()));
                    row.put("fullText", entry.getValue());
                    return row;
                })
                .toList();
    }

    private List<Map<String, Object>> routeStatuses() {
        List<String> routeIds = List.of(
                "software-team-director",
                "software-team-project-manager",
                "software-team-product-owner",
                "software-team-application-developer",
                "llm-response-consumer",
                "llm-worker"
        );
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String routeId : routeIds) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("routeId", routeId);
            ServiceStatus status = camelContext.getRouteController().getRouteStatus(routeId);
            row.put("status", status == null ? "UNKNOWN" : status.name());
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> summary(
            List<Map<String, Object>> routes,
            List<Map<String, Object>> topicDetails,
            List<Map<String, Object>> projects,
            List<Map<String, Object>> scratchpads) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("routes", routes.size());
        row.put("startedRoutes", routes.stream().filter(route -> "Started".equals(route.get("status"))).count());
        row.put("topics", topicDetails.size());
        row.put("totalTopicMessages", topicDetails.stream()
                .map(topic -> topic.get("messageCount"))
                .filter(Long.class::isInstance)
                .map(Long.class::cast)
                .reduce(0L, Long::sum));
        row.put("projects", projects.size());
        row.put("scratchpads", scratchpads.size());
        return row;
    }

    private static String preview(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 79) + "…";
    }
}
