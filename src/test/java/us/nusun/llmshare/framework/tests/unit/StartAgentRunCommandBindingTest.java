package us.nusun.llmshare.framework.tests.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.model.StartAgentRunCommand;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StartAgentRunCommandBindingTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void jsonCommandPreservesNestedInput() {
        StartAgentRunCommand command = StartAgentRunCommand.fromJson(nestedCommandJson(), OBJECT_MAPPER);

        Map<String, Object> input = command.input();
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
    void mapCommandPreservesNestedInputAndUsesShallowImmutableInputMap() {
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
        body.put("missionId", "mission-1");
        body.put("personaId", "persona-a");
        body.put("routineId", "routine-a");
        body.put("input", input);

        StartAgentRunCommand command = StartAgentRunCommand.fromMap(body, OBJECT_MAPPER);

        assertInstanceOf(Map.class, command.input().get("config"));
        assertInstanceOf(List.class, command.input().get("tags"));
        assertEquals(5, ((Number) ((Map<?, ?>) ((Map<?, ?>) command.input().get("config")).get("limits")).get("maxTasks")).intValue());
        assertEquals(List.of("planning", "delivery"), command.input().get("tags"));
        assertNull(command.input().get("optional"));
        assertThrows(UnsupportedOperationException.class, () -> command.input().put("new", "value"));
    }

    @Test
    void jsonRoundTripPreservesStructuredInput() throws Exception {
        StartAgentRunCommand original = StartAgentRunCommand.fromJson(nestedCommandJson(), OBJECT_MAPPER);
        String json = OBJECT_MAPPER.writeValueAsString(original);
        StartAgentRunCommand roundTripped = StartAgentRunCommand.fromJson(json, OBJECT_MAPPER);

        assertFalse(json.contains("\\\"limits\\\""));
        assertInstanceOf(Map.class, roundTripped.input().get("config"));
        assertInstanceOf(List.class, roundTripped.input().get("tags"));
        assertEquals(true, roundTripped.input().get("enabled"));
        assertEquals(0.87, ((Number) roundTripped.input().get("score")).doubleValue(), 0.0001);
        assertEquals(5, ((Number) ((Map<?, ?>) ((Map<?, ?>) roundTripped.input().get("config")).get("limits")).get("maxTasks")).intValue());
        assertNull(roundTripped.input().get("optional"));
    }

    private static String nestedCommandJson() {
        return """
                {
                  "missionId": "mission-1",
                  "personaId": "persona-a",
                  "routineId": "routine-a",
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
