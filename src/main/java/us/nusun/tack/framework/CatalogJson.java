package us.nusun.tack.framework;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class CatalogJson {
    private final ObjectMapper objectMapper;

    public CatalogJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(Object value, String catalogName) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to render " + catalogName + " catalog JSON", e);
        }
    }
}
