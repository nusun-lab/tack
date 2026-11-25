package us.nusun.tack.framework.run;

import java.time.Instant;
import java.util.List;

public record Mission(
        String id,
        String goal,
        List<String> constraints,
        String status,
        Instant createdAt
) {
    public Mission {
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
