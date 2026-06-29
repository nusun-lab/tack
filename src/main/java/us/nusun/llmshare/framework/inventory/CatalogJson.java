package us.nusun.llmshare.framework.inventory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

final class CatalogJson {
    private final ObjectMapper objectMapper;

    CatalogJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String write(Object value, String catalogName) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to render " + catalogName + " catalog JSON", e);
        }
    }
}
