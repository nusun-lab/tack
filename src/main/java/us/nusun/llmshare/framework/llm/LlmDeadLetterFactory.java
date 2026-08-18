package us.nusun.llmshare.framework.llm;

import static us.nusun.llmshare.framework.model.FrameworkText.firstNonBlank;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.Exchange;
import us.nusun.llmshare.framework.model.InferenceLaneDefinition;
import us.nusun.llmshare.framework.model.LlmJobDeadLetter;
import us.nusun.llmshare.framework.model.LlmJob;
import us.nusun.llmshare.framework.model.LlmJobResult;
import us.nusun.llmshare.framework.model.Persona;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class LlmDeadLetterFactory {
    private static final int MAX_DLQ_METADATA_STRING_LENGTH = 512;

    public LlmJobDeadLetter failedJobDeadLetter(Exchange exchange) {
        return failedJobDeadLetter(exchange, globalModelFallback(exchange));
    }

    private LlmJobDeadLetter failedJobDeadLetter(Exchange exchange, String globalModelFallback) {
        LlmJob job = exchange.getProperty("llmJob", LlmJob.class);
        if (job == null) {
            throw new IllegalStateException("Cannot publish LLM DLQ entry without llmJob exchange property");
        }
        exchange.setProperty("llmJobId", job.jobId());
        return new LlmJobDeadLetter(
                job.jobId(),
                laneId(exchange, job),
                modelId(exchange, globalModelFallback),
                normalizeDlqMetadata(job.metadata()),
                firstNonBlank(exchange.getProperty("llmFailureMessage", String.class), "Unknown LLM job failure"),
                job.requestedAt(),
                Instant.now());
    }

    public LlmJobResult failedLlmResult(Exchange exchange) {
        return failedLlmResult(exchange, globalModelFallback(exchange));
    }

    private LlmJobResult failedLlmResult(Exchange exchange, String globalModelFallback) {
        Exception failure = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
        LlmJob job = exchange.getProperty("llmJob", LlmJob.class);
        String message = failureMessage(failure, "Unknown LLM worker failure");
        return LlmJobResult.failed(job, modelId(exchange, globalModelFallback), message,
                firstInstant(exchange.getProperty("llmStartedAt", Instant.class), job == null ? null : job.requestedAt(), Instant.now()));
    }

    public String contextSummary(Exchange exchange) {
        return contextSummary(exchange, globalModelFallback(exchange));
    }

    private String contextSummary(Exchange exchange, String globalModelFallback) {
        LlmJob job = exchange.getProperty("llmJob", LlmJob.class);
        return "jobId=" + firstNonBlank(exchange.getProperty("llmJobId", String.class), job == null ? null : String.valueOf(job.jobId()))
                + " laneId=" + laneId(exchange, job)
                + " model=" + modelId(exchange, globalModelFallback);
    }

    public String modelId(Exchange exchange) {
        return modelId(exchange, globalModelFallback(exchange));
    }

    private String modelId(Exchange exchange, String globalModelFallback) {
        return firstNonBlank(
                exchange.getProperty("model", String.class),
                exchange.getProperty("modelId", String.class),
                globalModelFallback,
                "unknown");
    }

    private String laneId(Exchange exchange, LlmJob job) {
        Object lane = exchange.getProperty("inferenceLane");
        if (lane instanceof InferenceLaneDefinition definition) {
            return definition.id();
        }
        if (lane instanceof String id && !id.isBlank()) {
            return id;
        }
        return firstNonBlank(
                exchange.getProperty("laneId", String.class),
                job == null ? null : job.laneId(),
                "unknown");
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

    private static Instant firstInstant(Instant... values) {
        for (Instant value : values) {
            if (value != null) {
                return value;
            }
        }
        return Instant.now();
    }

    private static String resolveOptionalPlaceholder(Exchange exchange, String placeholder) {
        try {
            return exchange.getContext().resolvePropertyPlaceholders(placeholder);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String globalModelFallback(Exchange exchange) {
        return resolveOptionalPlaceholder(exchange, "{{llm.model-default.openai.model:}}");
    }
}
