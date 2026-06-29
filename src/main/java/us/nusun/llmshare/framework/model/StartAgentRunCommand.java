package us.nusun.llmshare.framework.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StartAgentRunCommand(
        String missionId,
        String personaId,
        String routineId,
        String parentAgentRunId,
        String reason,
        Map<String, Object> input
) {
    public StartAgentRunCommand {
        if (personaId == null || personaId.isBlank()) {
            throw new IllegalArgumentException("AgentRun start command missing personaId");
        }
        if (routineId == null || routineId.isBlank()) {
            throw new IllegalArgumentException("AgentRun start command missing routineId");
        }
        input = immutableMapAllowingNulls(input);
    }

    public StartAgentRunCommand withDefaults(String defaultMissionId, String defaultParentAgentRunId) {
        return new StartAgentRunCommand(
                firstNonBlank(missionId, defaultMissionId),
                personaId,
                routineId,
                firstNonBlank(parentAgentRunId, defaultParentAgentRunId),
                reason,
                input);
    }

    public Map<String, Object> workingState() {
        LinkedHashMap<String, Object> state = new LinkedHashMap<>();
        putIfPresent(state, "parentAgentRunId", parentAgentRunId);
        putIfPresent(state, "reason", reason);
        if (!input.isEmpty()) {
            state.put("input", input);
        }
        return immutableMapAllowingNulls(state);
    }

    public static StartAgentRunCommand fromJson(String json, ObjectMapper objectMapper) {
        try {
            return objectMapper.readValue(json, StartAgentRunCommand.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid StartAgentRunCommand JSON", e);
        }
    }

    public static StartAgentRunCommand fromJson(JsonNode node, ObjectMapper objectMapper) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("AgentRun start command body must be an object");
        }
        try {
            return objectMapper.treeToValue(node, StartAgentRunCommand.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid StartAgentRunCommand JSON", e);
        }
    }

    public static StartAgentRunCommand fromMap(Map<?, ?> map, ObjectMapper objectMapper) {
        if (map == null) {
            throw new IllegalArgumentException("AgentRun start command body must be an object");
        }
        try {
            return objectMapper.convertValue(map, StartAgentRunCommand.class);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid StartAgentRunCommand map", e);
        }
    }

    public static StartAgentRunCommand fromBody(Object body, ObjectMapper objectMapper) {
        if (body instanceof StartAgentRunCommand command) {
            return command;
        }
        if (body instanceof String json) {
            return fromJson(json, objectMapper);
        }
        if (body instanceof JsonNode node) {
            return fromJson(node, objectMapper);
        }
        if (body instanceof Map<?, ?> map) {
            return fromMap(map, objectMapper);
        }
        if (body == null) {
            throw new IllegalArgumentException("AgentRun start command body is required");
        }
        try {
            return objectMapper.convertValue(body, StartAgentRunCommand.class);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid StartAgentRunCommand body", e);
        }
    }

    private static void putIfPresent(Map<String, Object> state, String key, Object value) {
        if (value instanceof String text) {
            if (!text.isBlank()) state.put(key, text);
        } else if (value != null) {
            state.put(key, value);
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static Map<String, Object> immutableMapAllowingNulls(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
