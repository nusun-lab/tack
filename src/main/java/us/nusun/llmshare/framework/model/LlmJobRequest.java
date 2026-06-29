package us.nusun.llmshare.framework.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record LlmJobRequest(
        UUID jobId,
        String requester,
        String role,
        int priority,
        String prompt,
        String systemPrompt,
        String responseSchema,
        Map<String, Object> metadata,
        Instant requestedAt
) {
    public static final String JSON_ONLY_SUFFIX = "Return valid JSON only. Do not include labels, markdown fences, comments, prefixes, or explanatory text.";

    public static LlmJobRequest fromPrompt(UUID jobId, String requester, String role, String prompt, String systemPrompt, String responseSchema) {
        return new LlmJobRequest(jobId, requester, role, 0, prompt, systemPrompt, responseSchema, Map.of(), null);
    }

    public LlmJobRequest withJobId(UUID newJobId) {
        return new LlmJobRequest(newJobId, requester, role, priority, prompt, systemPrompt, responseSchema, metadata, requestedAt);
    }

    public LlmJobRequest normalized() {
        LlmJobRequest normalized = new LlmJobRequest(
                jobId == null ? UUID.randomUUID() : jobId,
                blankToDefault(requester, "synthesis-low"),
                blankToDefault(role, "framework"),
                priority,
                prompt,
                systemPrompt,
                responseSchema,
                metadata == null ? Map.of() : Map.copyOf(metadata),
                requestedAt == null ? Instant.now() : requestedAt);
        normalized.validate();
        return normalized;
    }

    public LlmJobRequest ensureJsonOnlyResponse() {
        if (responseSchema == null || responseSchema.isBlank()) {
            return this;
        }
        String nextSystemPrompt = systemPrompt == null || systemPrompt.isBlank()
                ? JSON_ONLY_SUFFIX
                : systemPrompt.contains("Return valid JSON only") ? systemPrompt : systemPrompt + "\n" + JSON_ONLY_SUFFIX;
        return new LlmJobRequest(jobId, requester, role, priority, prompt, nextSystemPrompt, responseSchema, metadata, requestedAt);
    }

    public void validate() {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }
}
