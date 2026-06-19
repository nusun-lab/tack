package us.nusun.llmshare.bean;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.Exchange;
import us.nusun.llmshare.model.DirectorStateRecord;
import us.nusun.llmshare.model.ProjectBasicsStateRecord;
import us.nusun.llmshare.model.ScratchpadStateRecord;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class ProjectMemoryStore {
    private final Map<String, String> projectBasicsByRequest = new ConcurrentHashMap<>();
    private final Map<String, DirectorStateRecord> directorStateByRequest = new ConcurrentHashMap<>();
    private final Map<String, String> scratchpadByRequestAndRole = new ConcurrentHashMap<>();
    private final Map<String, ScratchpadStats> scratchpadStatsByRequestAndRole = new ConcurrentHashMap<>();

    public String projectKey(Exchange exchange) {
        Object requestId = exchange.getProperty("requestId");
        if (requestId != null) {
            return String.valueOf(requestId);
        }
        Object bodyRequestId = exchange.getProperty("bodyRequestId");
        if (bodyRequestId != null) {
            return String.valueOf(bodyRequestId);
        }
        return exchange.getExchangeId();
    }

    public String roleKey(Exchange exchange) {
        return projectKey(exchange) + "::" + exchange.getIn().getHeader("agent", String.class);
    }

    public String roleKey(Exchange exchange, String roleId) {
        return projectKey(exchange) + "::" + roleId;
    }

    public String getProjectBasics(Exchange exchange) {
        return projectBasicsByRequest.get(projectKey(exchange));
    }

    public void putProjectBasics(Exchange exchange, String value) {
        projectBasicsByRequest.put(projectKey(exchange), value);
    }

    public String getAgentScratchpad(Exchange exchange) {
        return scratchpadByRequestAndRole.get(roleKey(exchange));
    }

    public String getAgentScratchpad(Exchange exchange, String roleId) {
        return scratchpadByRequestAndRole.get(roleKey(exchange, roleId));
    }

    public void putAgentScratchpad(Exchange exchange, String value) {
        putAgentScratchpad(exchange, exchange.getIn().getHeader("agent", String.class), value);
    }

    public void putAgentScratchpad(Exchange exchange, String roleId, String value) {
        String key = roleKey(exchange, roleId);
        scratchpadByRequestAndRole.put(key, value);
        scratchpadStatsByRequestAndRole.computeIfAbsent(key, ignored -> new ScratchpadStats()).recordWrite();
    }

    public void applyProjectBasics(ProjectBasicsStateRecord record) {
        if (record == null || record.requestId() == null || record.requestId().isBlank()) {
            return;
        }
        projectBasicsByRequest.put(record.requestId(), record.projectBasics());
    }

    public void applyDirectorState(DirectorStateRecord record) {
        if (record == null || record.requestId() == null || record.requestId().isBlank()) {
            return;
        }
        directorStateByRequest.put(record.requestId(), record);
    }

    public DirectorStateRecord getDirectorState(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        return directorStateByRequest.get(requestId);
    }

    public void applyScratchpad(ScratchpadStateRecord record) {
        if (record == null || record.requestId() == null || record.requestId().isBlank()
                || record.roleId() == null || record.roleId().isBlank()) {
            return;
        }
        String key = record.requestId() + "::" + record.roleId();
        scratchpadByRequestAndRole.put(key, record.scratchpad());
        scratchpadStatsByRequestAndRole
                .computeIfAbsent(key, ignored -> new ScratchpadStats())
                .apply(record.messageCount(), record.lastUpdatedAt());
    }

    public int currentScratchpadMessageCount(String requestId, String roleId) {
        ScratchpadStatsSnapshot snapshot = snapshotScratchpadStats().get(requestId + "::" + roleId);
        return snapshot == null ? 0 : snapshot.writeCount();
    }

    public Map<String, String> snapshotProjectBasics() {
        return new LinkedHashMap<>(projectBasicsByRequest);
    }

    public Map<String, DirectorStateRecord> snapshotDirectorStates() {
        return new LinkedHashMap<>(directorStateByRequest);
    }

    public Map<String, String> snapshotScratchpads() {
        return new LinkedHashMap<>(scratchpadByRequestAndRole);
    }

    public Map<String, ScratchpadStatsSnapshot> snapshotScratchpadStats() {
        LinkedHashMap<String, ScratchpadStatsSnapshot> rows = new LinkedHashMap<>();
        scratchpadStatsByRequestAndRole.forEach((key, value) -> rows.put(key, value.snapshot()));
        return rows;
    }

    public void clear() {
        projectBasicsByRequest.clear();
        directorStateByRequest.clear();
        scratchpadByRequestAndRole.clear();
        scratchpadStatsByRequestAndRole.clear();
    }

    public void removeProjectBasics(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        projectBasicsByRequest.remove(requestId);
    }

    public void removeDirectorState(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        directorStateByRequest.remove(requestId);
    }

    public void removeScratchpad(String requestId, String roleId) {
        if (requestId == null || requestId.isBlank() || roleId == null || roleId.isBlank()) {
            return;
        }
        String key = requestId + "::" + roleId;
        scratchpadByRequestAndRole.remove(key);
        scratchpadStatsByRequestAndRole.remove(key);
    }

    public java.util.List<String> scratchpadRoleIdsForRequest(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return java.util.List.of();
        }
        return scratchpadByRequestAndRole.keySet().stream()
                .filter(key -> key.startsWith(requestId + "::"))
                .map(key -> key.substring((requestId + "::").length()))
                .sorted()
                .toList();
    }

    public record ScratchpadStatsSnapshot(
            int writeCount,
            String lastUpdatedAt
    ) {
    }

    private static final class ScratchpadStats {
        private final AtomicInteger writeCount = new AtomicInteger();
        private volatile Instant lastUpdatedAt;

        private void recordWrite() {
            writeCount.incrementAndGet();
            lastUpdatedAt = Instant.now();
        }

        private void apply(int messageCount, String lastUpdatedAt) {
            writeCount.set(Math.max(0, messageCount));
            this.lastUpdatedAt = lastUpdatedAt == null || lastUpdatedAt.isBlank()
                    ? Instant.now()
                    : Instant.parse(lastUpdatedAt);
        }

        private ScratchpadStatsSnapshot snapshot() {
            return new ScratchpadStatsSnapshot(
                    writeCount.get(),
                    lastUpdatedAt == null ? null : lastUpdatedAt.toString());
        }
    }
}
