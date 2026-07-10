package us.nusun.llmshare.framework.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record LlmJobRequest(
        UUID jobId,
        String laneId,
        LlmPassType passType,
        String requester,
        String role,
        int priority,
        String prompt,
        String systemPrompt,
        String responseSchema,
        Map<String, Object> metadata,
        Instant requestedAt
) {
    public static final String DEFAULT_LANE = "low-pass";
    public static final LlmPassType DEFAULT_PASS_TYPE = LlmPassType.SYNTHESIS;

    public static LlmJobRequest fromPrompt(UUID jobId, String laneId, LlmPassType passType, String requester, String role, String prompt, String systemPrompt, String responseSchema) {
        return new LlmJobRequest(jobId, laneId, passType, requester, role, 0, prompt, systemPrompt, responseSchema, Map.of(), null);
    }

    public LlmJobRequest withJobId(UUID newJobId) {
        return new LlmJobRequest(newJobId, laneId, passType, requester, role, priority, prompt, systemPrompt, responseSchema, metadata, requestedAt);
    }

    public LlmJobRequest withLaneAndPass(String newLaneId, LlmPassType newPassType) {
        return new LlmJobRequest(jobId, firstNonBlank(newLaneId, laneId), newPassType == null ? passType : newPassType, requester, role, priority, prompt, systemPrompt, responseSchema, metadata, requestedAt);
    }

    public LlmJobRequest normalized() {
        LlmJobRequest normalized = new LlmJobRequest(
                jobId == null ? UUID.randomUUID() : jobId,
                blankToDefault(laneId, DEFAULT_LANE),
                passType == null ? DEFAULT_PASS_TYPE : passType,
                blankToDefault(requester, "framework"),
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
        String jsonOnlySuffix = """
                Return valid JSON only. Do not include labels, markdown fences, comments, prefixes, or explanatory text.
                """.strip();
        String nextSystemPrompt = systemPrompt == null || systemPrompt.isBlank()
                ? jsonOnlySuffix
                : systemPrompt.contains("Return valid JSON only") ? systemPrompt : systemPrompt + "\n" + jsonOnlySuffix;
        return new LlmJobRequest(jobId, laneId, passType, requester, role, priority, prompt, nextSystemPrompt, responseSchema, metadata, requestedAt);
    }

    public void validate() {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }
}
