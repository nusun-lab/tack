package us.nusun.tack.framework.routine;

import com.fasterxml.jackson.databind.JsonNode;

public record CreateResult(String createId, String schemaId, JsonNode object) implements PrimitiveResult {
}
