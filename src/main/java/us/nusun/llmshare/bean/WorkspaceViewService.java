package us.nusun.llmshare.bean;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.camel.CamelContext;
import org.apache.camel.ServiceStatus;
import us.nusun.llmshare.team.AgentPersonaDefinition;
import us.nusun.llmshare.team.AgentPersonaRegistry;

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

    @Inject
    AgentPersonaRegistry agentPersonaRegistry;

    @Inject
    AgentActivityTracker agentActivityTracker;

    public Map<String, Object> view() {
        List<Map<String, Object>> routes = routeStatuses();
        List<Map<String, Object>> projects = projectViews();
        List<Map<String, Object>> scratchpads = scratchpadViews();
        List<Map<String, Object>> teamRegistry = teamRegistryViews();
        List<Map<String, Object>> topicDetails = kafkaTopicProvisioner.topicDetails();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("timestamp", Instant.now().toString());
        response.put("topics", kafkaTopicProvisioner.configuredTopicNames());
        response.put("teamContext", teamContextView());
        response.put("teamRegistry", teamRegistry);
        response.put("routes", routes);
        response.put("projects", projects);
        response.put("scratchpads", scratchpads);
        response.put("topicDetails", topicDetails);
        response.put("summary", summary(routes, topicDetails, projects, scratchpads, teamRegistry));
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
        Map<String, ProjectMemoryStore.ScratchpadStatsSnapshot> stats = projectMemoryStore.snapshotScratchpadStats();
        return projectMemoryStore.snapshotScratchpads().entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(entry -> {
                    String key = entry.getKey();
                    int split = key.indexOf("::");
                    String requestId = split >= 0 ? key.substring(0, split) : key;
                    String roleId = split >= 0 ? key.substring(split + 2) : "";
                    ProjectMemoryStore.ScratchpadStatsSnapshot stat = stats.get(key);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("requestId", requestId);
                    row.put("roleId", roleId);
                    row.put("scratchpad", entry.getValue());
                    row.put("length", entry.getValue() == null ? 0 : entry.getValue().length());
                    row.put("preview", preview(entry.getValue()));
                    row.put("fullText", entry.getValue());
                    row.put("messageCount", stat == null ? 0 : stat.writeCount());
                    row.put("lastActivity", stat == null ? null : stat.lastUpdatedAt());
                    return row;
                })
                .toList();
    }

    private List<Map<String, Object>> routeStatuses() {
        List<String> routeIds = new ArrayList<>(agentPersonaRegistry.teamPersonas().stream()
                .map(AgentPersonaDefinition::routeId)
                .toList());
        routeIds.add("llm-response-consumer");
        routeIds.add("llm-worker");
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

    private List<Map<String, Object>> teamRegistryViews() {
        Map<String, String> latestScratchpadByRole = new LinkedHashMap<>();
        Map<String, ProjectMemoryStore.ScratchpadStatsSnapshot> latestStatsByRole = new LinkedHashMap<>();
        Map<String, AgentActivityTracker.AgentActivitySnapshot> activityByRole = agentActivityTracker.snapshot();
        projectMemoryStore.snapshotScratchpads().forEach((key, value) -> {
            String roleId = roleIdFromKey(key);
            latestScratchpadByRole.put(roleId, value);
        });
        projectMemoryStore.snapshotScratchpadStats().forEach((key, value) -> {
            String roleId = roleIdFromKey(key);
            ProjectMemoryStore.ScratchpadStatsSnapshot existing = latestStatsByRole.get(roleId);
            if (existing == null || compareTime(existing.lastUpdatedAt(), value.lastUpdatedAt()) <= 0) {
                latestStatsByRole.put(roleId, value);
            }
        });

        return agentPersonaRegistry.teamPersonas().stream()
                .map(persona -> {
                    String roleId = persona.roleId();
                    ServiceStatus routeStatus = camelContext.getRouteController().getRouteStatus(persona.routeId());
                    ProjectMemoryStore.ScratchpadStatsSnapshot stats = latestStatsByRole.get(roleId);
                    AgentActivityTracker.AgentActivitySnapshot activity = activityByRole.get(roleId);
                    String scratchpad = latestScratchpadByRole.get(roleId);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("roleId", roleId);
                    row.put("roleSlug", persona.roleSlug());
                    row.put("displayName", persona.displayName());
                    row.put("agentLabel", persona.agentLabel());
                    row.put("ansiColor", persona.ansiColor());
                    row.put("capabilitySummary", persona.capabilitySummary());
                    row.put("routeId", persona.routeId());
                    row.put("routeUri", persona.routeUri());
                    row.put("routeStatus", routeStatus == null ? "UNKNOWN" : routeStatus.name());
                    row.put("activeNow", activity != null && activity.activeNow());
                    row.put("loopStatus", activity != null && activity.activeNow() ? "Active" : "Idle");
                    row.put("activeRequestId", activity == null ? null : activity.requestId());
                    row.put("currentStep", activity == null ? null : activity.currentStep());
                    row.put("currentRoutine", activity == null ? null : activity.currentRoutine());
                    row.put("activeCycleCount", activity == null ? null : activity.cycleCount());
                    row.put("activeDayCount", activity == null ? null : activity.dayCount());
                    row.put("messageCount", stats == null ? 0 : stats.writeCount());
                    row.put("lastActivity", latestActivity(stats == null ? null : stats.lastUpdatedAt(), activity == null ? null : activity.lastActivity()));
                    row.put("scratchpadSummary", blank(scratchpad) ? "No scratchpad yet" : preview(scratchpad));
                    row.put("scratchpad", blank(scratchpad) ? null : scratchpad);
                    row.put("routineStatus", activity != null && activity.activeNow()
                            ? "Running " + blankToFallback(activity.currentStep(), "loop")
                            : blank(scratchpad) ? "No scratchpad yet" : "Idle");
                    row.put("platformIdentities", persona.platformIdentities());
                    row.put("routineSteps", persona.routineSteps().stream().map(step -> {
                        Map<String, Object> stepRow = new LinkedHashMap<>();
                        stepRow.put("stepId", step.stepId());
                        stepRow.put("routine", step.routine());
                        return stepRow;
                    }).toList());
                    return row;
                })
                .toList();
    }

    private Map<String, Object> teamContextView() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("teamId", agentPersonaRegistry.teamContext().teamId());
        row.put("teamDisplayName", agentPersonaRegistry.teamContext().teamDisplayName());
        row.put("ownerDisplayName", agentPersonaRegistry.teamContext().ownerDisplayName());
        row.put("ownerIdentity", agentPersonaRegistry.teamContext().ownerIdentity());
        row.put("defaultCoordinationChannel", agentPersonaRegistry.teamContext().defaultCoordinationChannel());
        row.put("availableTools", agentPersonaRegistry.teamContext().availableTools());
        row.put("unavailableTools", agentPersonaRegistry.teamContext().unavailableTools());
        row.put("collaborationRules", agentPersonaRegistry.teamContext().collaborationRules());
        row.put("sharedConstraints", agentPersonaRegistry.teamContext().sharedConstraints());
        return row;
    }

    private Map<String, Object> summary(
            List<Map<String, Object>> routes,
            List<Map<String, Object>> topicDetails,
            List<Map<String, Object>> projects,
            List<Map<String, Object>> scratchpads,
            List<Map<String, Object>> teamRegistry) {
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
        row.put("personas", teamRegistry.size());
        row.put("activePersonas", teamRegistry.stream().filter(persona -> Boolean.TRUE.equals(persona.get("activeNow"))).count());
        return row;
    }

    private static String roleIdFromKey(String key) {
        int split = key.indexOf("::");
        return split >= 0 ? key.substring(split + 2) : key;
    }

    private static int compareTime(String left, String right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        return left.compareTo(right);
    }

    private static String latestActivity(String left, String right) {
        return compareTime(left, right) >= 0 ? left : right;
    }

    private static String blankToFallback(String value, String fallback) {
        return blank(value) ? fallback : value;
    }

    private static String preview(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 79) + "…";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
