package us.nusun.tack.framework.inference;

import static us.nusun.tack.framework.FrameworkText.firstNonBlank;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.Exchange;
import us.nusun.tack.framework.inference.lane.InferenceLaneDefinition;
import us.nusun.tack.framework.persona.Persona;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class LlmDeadLetterFactory {
    private static final int MAX_DLQ_METADATA_STRING_LENGTH = 512;

    public LlmJobDeadLetter failedQueuedJobDeadLetter(Exchange exchange) {
        var job = exchange.getProperty("llmJob", LlmJobEnvelope.class);
        if (job == null) {
            throw new IllegalStateException("Cannot publish LLM DLQ entry without llmJob exchange property");
        }
        var context = LlmExecutionContext.on(exchange);
        return new LlmJobDeadLetter(
                context == null ? job.jobId() : context.jobId(),
                context == null ? firstNonBlank(job.laneId(), "unknown") : context.laneId(),
                context == null ? job.passType() : context.passType(),
                job.requester(), job.role(), modelId(exchange), normalizeDlqMetadata(job.metadata()),
                firstNonBlank(exchange.getProperty("llmFailureMessage", String.class), "Unknown LLM queue failure"),
                job.requestedAt(), Instant.now());
    }

    public LlmJobResult failedLlmResult(Exchange exchange) {
        Exception failure = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
        var job = exchange.getProperty("llmJob", LlmJobEnvelope.class);
        Instant startedAt = exchange.getProperty("llmStartedAt", Instant.class);
        return LlmJobResult.failed(job, modelId(exchange), failureMessage(failure, "Unknown LLM worker failure"), null,
                startedAt == null ? job.requestedAt() : startedAt);
    }

    public String contextSummary(Exchange exchange) {
        var context = LlmExecutionContext.on(exchange);
        var job = exchange.getProperty("llmJob", LlmJobEnvelope.class);
        if (context != null) {
            return "jobId=" + context.jobId() + " agentRunId=" + context.agentRunId()
                    + " primitive=" + context.primitive() + "/" + context.primitiveId() + " laneId=" + context.laneId()
                    + " passType=" + context.passType() + " model=" + modelId(exchange);
        }
        Map<String, Object> metadata = job == null ? Map.of() : job.metadata();
        return "jobId=" + (job == null ? null : job.jobId())
                + " agentRunId=" + LlmExecutionContext.text(metadata, "agentRunId")
                + " primitive=" + LlmExecutionContext.text(metadata, "primitive") + "/"
                + LlmExecutionContext.text(metadata, "primitiveId")
                + " laneId=" + firstNonBlank(job == null ? null : job.laneId(), "unknown")
                + " passType=" + (job == null ? null : job.passType()) + " model=" + modelId(exchange);
    }

    String modelId(Exchange exchange) {
        var context = LlmExecutionContext.on(exchange);
        if (context != null) {
            return firstNonBlank(context.model(), "unknown");
        }
        var job = exchange.getProperty("llmJob", LlmJobEnvelope.class);
        return firstNonBlank(job == null ? null : LlmExecutionContext.text(job.metadata(), "model"), "unknown");
    }

    private static Map<String, Object> normalizeDlqMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        metadata.forEach((key, value) -> normalized.put(boundedString(String.valueOf(key)), normalizeDlqMetadataValue(value)));
        return java.util.Collections.unmodifiableMap(normalized);
    }

    private static Object normalizeDlqMetadataValue(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof String text) {
            return boundedString(text);
        }
        if (value instanceof InferenceLaneDefinition lane) {
            return lane.id();
        }
        if (value instanceof Persona persona) {
            return persona.id();
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> normalized.put(boundedString(String.valueOf(key)), normalizeDlqMetadataValue(nestedValue)));
            return java.util.Collections.unmodifiableMap(normalized);
        }
        if (value instanceof Iterable<?> iterable) {
            ArrayList<Object> normalized = new ArrayList<>();
            for (Object item : iterable) {
                normalized.add(normalizeDlqMetadataValue(item));
            }
            return java.util.Collections.unmodifiableList(normalized);
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            ArrayList<Object> normalized = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                normalized.add(normalizeDlqMetadataValue(java.lang.reflect.Array.get(value, i)));
            }
            return java.util.Collections.unmodifiableList(normalized);
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        return boundedString(value.getClass().getName() + ": " + value);
    }

    private static String boundedString(String value) {
        if (value == null || value.length() <= MAX_DLQ_METADATA_STRING_LENGTH) {
            return value;
        }
        String suffix = "...(truncated, "
                + value.length()
                + " chars)";
        if (suffix.length() >= MAX_DLQ_METADATA_STRING_LENGTH) {
            return suffix.substring(0, MAX_DLQ_METADATA_STRING_LENGTH);
        }
        return value.substring(0, MAX_DLQ_METADATA_STRING_LENGTH - suffix.length()) + suffix;
    }

    private static String failureMessage(Throwable failure, String fallback) {
        if (failure == null) {
            return fallback;
        }
        String message = firstNonBlank(failure.getMessage(), failure.getClass().getSimpleName(), fallback);
        Throwable root = rootCause(failure);
        String rootMessage = root == failure ? null : firstNonBlank(root.getMessage(), root.getClass().getSimpleName());
        return rootMessage == null || rootMessage.equals(message) ? message : message + " (root cause: " + rootMessage + ")";
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
