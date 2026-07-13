package us.nusun.llmshare.framework.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentRunRequest(
        String personaId,
        String routineId,
        String subroutineId,
        Map<String, Object> input
) {
    public AgentRunRequest(String personaId, String routineId, Map<String, Object> input) {
        this(personaId, routineId, null, input);
    }

    public AgentRunRequest {
        if (personaId == null || personaId.isBlank()) {
            throw new IllegalArgumentException("AgentRun request missing personaId");
        }
        if (routineId == null || routineId.isBlank()) {
            throw new IllegalArgumentException("AgentRun request missing routineId");
        }
        personaId = personaId.trim();
        routineId = routineId.trim();
        subroutineId = RoutineDefinition.normalizeSubroutineId(subroutineId);
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
