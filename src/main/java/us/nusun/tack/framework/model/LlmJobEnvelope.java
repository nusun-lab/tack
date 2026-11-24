package us.nusun.tack.framework.model;

import static us.nusun.tack.framework.model.FrameworkText.firstNonBlankStripped;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record LlmJobEnvelope(
        UUID jobId,
        LlmJobStatus status,
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

    public LlmJobEnvelope {
        metadata = JsonValues.snapshot(metadata);
    }

    public LlmJobEnvelope(UUID jobId, String laneId, LlmPassType passType, String requester, String role, int priority,
                          String prompt, String systemPrompt, String responseSchema,
                          Map<String, Object> metadata, Instant requestedAt) {
        this(jobId, null, laneId, passType, requester, role, priority, prompt, systemPrompt, responseSchema, metadata,
                requestedAt);
    }

    public static LlmJobEnvelope queued(LlmJobEnvelope request) {
        LlmJobEnvelope normalized = request.normalized();
        return new LlmJobEnvelope(normalized.jobId(), LlmJobStatus.QUEUED, normalized.laneId(), normalized.passType(),
                normalized.requester(), normalized.role(), normalized.priority(), normalized.prompt(),
                normalized.systemPrompt(), normalized.responseSchema(), normalized.metadata(), normalized.requestedAt());
    }

    public LlmJobEnvelope withMetadata(Map<String, Object> metadata) {
        return new LlmJobEnvelope(jobId, status, laneId, passType, requester, role, priority, prompt,
                systemPrompt, responseSchema, metadata, requestedAt);
    }

    public static LlmJobEnvelope fromPrompt(UUID jobId, String laneId, LlmPassType passType, String requester, String role, String prompt, String systemPrompt, String responseSchema) {
        return new LlmJobEnvelope(jobId, laneId, passType, requester, role, 0, prompt, systemPrompt, responseSchema, Map.of(), null);
    }

    public LlmJobEnvelope withJobId(UUID newJobId) {
        return new LlmJobEnvelope(newJobId, laneId, passType, requester, role, priority, prompt, systemPrompt, responseSchema, metadata, requestedAt);
    }

    public LlmJobEnvelope withLaneAndPass(String newLaneId, LlmPassType newPassType) {
        return new LlmJobEnvelope(jobId, firstNonBlankStripped(newLaneId, laneId), newPassType == null ? passType : newPassType, requester, role, priority, prompt, systemPrompt, responseSchema, metadata, requestedAt);
    }

    public LlmJobEnvelope normalized() {
        LlmJobEnvelope normalized = new LlmJobEnvelope(
                jobId == null ? UUID.randomUUID() : jobId,
                blankToDefault(laneId, DEFAULT_LANE),
                passType == null ? DEFAULT_PASS_TYPE : passType,
                blankToDefault(requester, "framework"),
                blankToDefault(role, "framework"),
                priority,
                prompt,
                systemPrompt,
                responseSchema,
                metadata,
                requestedAt == null ? Instant.now() : requestedAt);
        normalized.validate();
        return normalized;
    }

    public LlmJobEnvelope ensureJsonOnlyResponse() {
        if (responseSchema == null || responseSchema.isBlank()) {
            return this;
        }
        String jsonOnlySuffix = """
                Return valid JSON only. Do not include labels, markdown fences, comments, prefixes, or explanatory text.
                """.strip();
        String nextSystemPrompt = systemPrompt == null || systemPrompt.isBlank()
                ? jsonOnlySuffix
                : systemPrompt.contains("Return valid JSON only") ? systemPrompt : systemPrompt + "\n" + jsonOnlySuffix;
        return new LlmJobEnvelope(jobId, laneId, passType, requester, role, priority, prompt, nextSystemPrompt, responseSchema, metadata, requestedAt);
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
