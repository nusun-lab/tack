package us.nusun.tack.framework.model;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Captures JSON-shaped input without changing scalar types or dropping nulls. */
final class JsonValues {
    private JsonValues() {}

    static <K> Map<K, Object> snapshot(Map<K, ?> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        var copy = new LinkedHashMap<K, Object>();
        values.forEach((key, value) -> copy.put(key, freeze(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            return snapshot(map);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(JsonValues::freeze).toList();
        }
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            var copy = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                copy.add(freeze(Array.get(value, index)));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
