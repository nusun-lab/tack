package us.nusun.tack.framework.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentRunRequestBindingTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void jacksonJsonRequestPreservesNestedInput() throws Exception {
        AgentRunRequest request = OBJECT_MAPPER.readValue(nestedRequestJson(), AgentRunRequest.class);

        Map<String, Object> input = request.input();
        assertEquals("phase-a", request.subroutineId());
        assertEquals("pm", input.get("role"));
        assertInstanceOf(String.class, input.get("role"));
        assertEquals(true, input.get("enabled"));
        assertInstanceOf(Boolean.class, input.get("enabled"));
        assertEquals(0.87, ((Number) input.get("score")).doubleValue(), 0.0001);
        assertFalse(input.get("score") instanceof String);
        assertNull(input.get("optional"));

        Map<?, ?> config = assertInstanceOf(Map.class, input.get("config"));
        assertFalse(input.get("config") instanceof String);
        Map<?, ?> limits = assertInstanceOf(Map.class, config.get("limits"));
        assertEquals(5, ((Number) limits.get("maxTasks")).intValue());
        assertFalse(limits.get("maxTasks") instanceof String);

        List<?> tags = assertInstanceOf(List.class, input.get("tags"));
        assertEquals(List.of("planning", "delivery"), tags);
        assertFalse(input.get("tags") instanceof String);
    }

    @Test
    void mapRequestPreservesNestedInputAndUsesShallowImmutableInputMap() {
        LinkedHashMap<String, Object> limits = new LinkedHashMap<>();
        limits.put("maxTasks", 5);
        LinkedHashMap<String, Object> config = new LinkedHashMap<>();
        config.put("priority", "high");
        config.put("limits", limits);
        ArrayList<String> tags = new ArrayList<>(List.of("planning", "delivery"));
        LinkedHashMap<String, Object> input = new LinkedHashMap<>();
        input.put("role", "pm");
        input.put("config", config);
        input.put("tags", tags);
        input.put("enabled", true);
        input.put("score", 0.87);
        input.put("optional", null);
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("personaId", "persona-a");
        body.put("routineId", "routine-a");
        body.put("input", input);

        AgentRunRequest request = OBJECT_MAPPER.convertValue(body, AgentRunRequest.class);

        assertInstanceOf(Map.class, request.input().get("config"));
        assertInstanceOf(List.class, request.input().get("tags"));
        assertEquals(5, ((Number) ((Map<?, ?>) ((Map<?, ?>) request.input().get("config")).get("limits")).get("maxTasks")).intValue());
        assertEquals(List.of("planning", "delivery"), request.input().get("tags"));
        assertNull(request.input().get("optional"));
        assertThrows(UnsupportedOperationException.class, () -> request.input().put("new", "value"));
    }

    @Test
    void jsonRoundTripPreservesStructuredInputAndNoOperationalFieldsAreRequired() throws Exception {
        AgentRunRequest original = OBJECT_MAPPER.readValue(nestedRequestJson(), AgentRunRequest.class);
        String json = OBJECT_MAPPER.writeValueAsString(original);
        AgentRunRequest roundTripped = OBJECT_MAPPER.readValue(json, AgentRunRequest.class);

        assertFalse(json.contains("missionId"));
        assertFalse(json.contains("parentAgentRunId"));
        assertFalse(json.contains("status"));
        assertEquals("phase-a", roundTripped.subroutineId());
        assertInstanceOf(Map.class, roundTripped.input().get("config"));
        assertEquals(5, ((Number) ((Map<?, ?>) ((Map<?, ?>) roundTripped.input().get("config")).get("limits")).get("maxTasks")).intValue());
    }


    @Test
    void blankSubroutineIdNormalizesToFullRoutineTarget() {
        AgentRunRequest request = new AgentRunRequest("persona-a", "routine-a", "   ", Map.of());

        assertNull(request.subroutineId());
    }

    private static String nestedRequestJson() {
        return """
                {
                  "personaId": "persona-a",
                  "routineId": "routine-a",
                  "subroutineId": "phase-a",
                  "input": {
                    "role": "pm",
                    "config": {
                      "priority": "high",
                      "limits": {
                        "maxTasks": 5
                      }
                    },
                    "tags": ["planning", "delivery"],
                    "enabled": true,
                    "score": 0.87,
                    "optional": null
                  }
                }
                """;
    }
}
