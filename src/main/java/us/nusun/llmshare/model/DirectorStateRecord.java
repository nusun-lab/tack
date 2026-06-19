package us.nusun.llmshare.model;

import java.util.Map;

public record DirectorStateRecord(
        String requestId,
        String goal,
        Map<String, String> assignments,
        String lastUpdatedAt
) {
}
