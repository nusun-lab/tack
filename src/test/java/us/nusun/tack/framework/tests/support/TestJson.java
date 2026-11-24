package us.nusun.tack.framework.tests.support;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class TestJson {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private TestJson() {
    }

    public static String write(Object value) throws Exception {
        return OBJECT_MAPPER.writeValueAsString(value);
    }

    public static <T> T read(String json, Class<T> type) throws Exception {
        return OBJECT_MAPPER.readValue(json, type);
    }
}
