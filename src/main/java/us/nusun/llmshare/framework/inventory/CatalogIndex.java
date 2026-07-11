package us.nusun.llmshare.framework.inventory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

final class CatalogIndex {
    private CatalogIndex() {
    }

    static <T> Map<String, T> uniqueById(
            List<T> rows,
            Function<T, String> id,
            String duplicateMessagePrefix) {
        LinkedHashMap<String, T> indexed = new LinkedHashMap<>();
        for (T row : rows == null ? List.<T>of() : rows) {
            String key = id.apply(row);
            if (indexed.put(key, row) != null) {
                throw new IllegalArgumentException(duplicateMessagePrefix + key);
            }
        }
        return Map.copyOf(indexed);
    }
}
