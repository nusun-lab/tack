package us.nusun.tack.framework.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record Mission(
        String id,
        String goal,
        List<String> constraints,
        String status,
        Map<String, Object> artifacts,
        Instant createdAt
) {
    public Mission {
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        artifacts = artifacts == null ? Map.of() : Map.copyOf(artifacts);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
