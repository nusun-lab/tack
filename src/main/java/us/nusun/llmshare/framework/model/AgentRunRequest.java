package us.nusun.llmshare.framework.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentRunRequest(
        String personaId,
        String routineId,
        Map<String, Object> input
) {
    private static final ObjectMapper DEFAULT_OBJECT_MAPPER = new ObjectMapper();

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

    public static AgentRunRequest fromJson(String json, ObjectMapper objectMapper) {
        try {
            return objectMapper.readValue(json, AgentRunRequest.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid AgentRunRequest JSON", e);
        }
    }

    public static AgentRunRequest fromJson(JsonNode node, ObjectMapper objectMapper) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("AgentRun request body must be an object");
        }
        try {
            return objectMapper.treeToValue(node, AgentRunRequest.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid AgentRunRequest JSON", e);
        }
    }

    public static AgentRunRequest fromMap(Map<?, ?> map, ObjectMapper objectMapper) {
        if (map == null) {
            throw new IllegalArgumentException("AgentRun request body must be an object");
        }
        try {
            return objectMapper.convertValue(map, AgentRunRequest.class);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid AgentRunRequest map", e);
        }
    }

    public static AgentRunRequest fromBody(Object body, ObjectMapper objectMapper) {
        if (body instanceof AgentRunRequest request) {
            return request;
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
            throw new IllegalArgumentException("AgentRun request body is required");
        }
        try {
            return objectMapper.convertValue(body, AgentRunRequest.class);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid AgentRunRequest body", e);
        }
    }

    public static AgentRunRequest fromBody(Object body) {
        return fromBody(body, DEFAULT_OBJECT_MAPPER);
    }

    private static Map<String, Object> immutableMapAllowingNulls(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
