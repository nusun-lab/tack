package us.nusun.llmshare.framework.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentRunRequest(
        String personaId,
        String routineId,
        Map<String, Object> input
) {
    public AgentRunRequest {
        if (personaId == null || personaId.isBlank()) {
            throw new IllegalArgumentException("AgentRun request missing personaId");
        }
        if (routineId == null || routineId.isBlank()) {
            throw new IllegalArgumentException("AgentRun request missing routineId");
        }
        input = immutableMapAllowingNulls(input);
    }

    public Map<String, Object> workingState() {
        if (input.isEmpty()) {
            return Map.of();
        }
        return Map.of("input", input);
    }

    private static Map<String, Object> immutableMapAllowingNulls(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
