package us.nusun.tack.framework.model;

import com.fasterxml.jackson.databind.JsonNode;

public record CreateResult(String createId, String schemaId, JsonNode object) {
}
