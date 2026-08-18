package us.nusun.llmshare.framework.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record LlmJob(
        UUID jobId,
        String laneId,
        String prompt,
        String systemPrompt,
        String responseSchema,
        Map<String, Object> metadata,
        Instant requestedAt
) {
    public LlmJob {
        if (jobId == null) {
            throw new IllegalArgumentException("jobId is required");
        }
        if (laneId == null || laneId.isBlank()) {
            throw new IllegalArgumentException("laneId is required");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }
        laneId = laneId.strip();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        requestedAt = requestedAt == null ? Instant.now() : requestedAt;
    }

    public static LlmJob create(
            String laneId,
            String prompt,
            String systemPrompt,
            String responseSchema,
            Map<String, Object> metadata) {
        return new LlmJob(
                UUID.randomUUID(),
                laneId,
                prompt,
                jsonOnlySystemPrompt(systemPrompt, responseSchema),
                responseSchema,
                metadata,
                Instant.now());
    }

    private static String jsonOnlySystemPrompt(String systemPrompt, String responseSchema) {
        if (responseSchema == null || responseSchema.isBlank()) {
            return systemPrompt;
        }
        String suffix = "Return valid JSON only. Do not include labels, markdown fences, comments, prefixes, or explanatory text.";
        if (systemPrompt == null || systemPrompt.isBlank()) {
            return suffix;
        }
        return systemPrompt.contains("Return valid JSON only") ? systemPrompt : systemPrompt + "\n" + suffix;
    }
}
