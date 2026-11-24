package us.nusun.tack.framework.model;

import java.util.Map;

public final class FrameworkText {
    private FrameworkText() {
    }

    public static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static String firstNonBlankStripped(String... values) {
        String value = firstNonBlank(values);
        return value == null ? null : value.strip();
    }

    public static void putIfNonBlank(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    public static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
