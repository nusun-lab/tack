package us.nusun.llmshare.framework.model;

import com.fasterxml.jackson.databind.JsonNode;

public record CreateResult(String createId, String schemaId, JsonNode object) {
}
