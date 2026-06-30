package us.nusun.llmshare.framework.llm;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.Exchange;
import us.nusun.llmshare.framework.inventory.ExecutionRuntimeRegistry;
import us.nusun.llmshare.framework.inventory.PersonaCatalog;
import us.nusun.llmshare.framework.model.ExecutionRuntimeDefinition;
import us.nusun.llmshare.framework.model.InferenceLaneDefinition;
import us.nusun.llmshare.framework.model.LlmJobEnvelope;
import us.nusun.llmshare.framework.model.LlmJobRequest;
import us.nusun.llmshare.framework.model.Mission;
import us.nusun.llmshare.framework.model.PartitionKeyStrategy;
import us.nusun.llmshare.framework.model.Persona;
import us.nusun.llmshare.framework.state.AgentRun;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class LlmJobFactory {
    public void prepare(
            Exchange exchange,
            ExecutionRuntimeRegistry runtimes,
            PersonaCatalog personas,
            String defaultRuntimeId,
            String globalModelFallback) {
        Object body = exchange.getIn().getBody();
        UUID overrideJobId = exchange.getProperty("llmJobId", UUID.class);
        UUID headerJobId = readJobId(exchange.getIn().getHeader("jobId", String.class));
        UUID jobId = overrideJobId != null ? overrideJobId : headerJobId;
        String runtimeId = firstNonBlank(
                exchange.getIn().getHeader("executionRuntime", String.class),
                exchange.getProperty("executionRuntimeId", String.class),
                defaultRuntimeId);

        LlmJobRequest request = body instanceof LlmJobRequest existing
                ? normalize(existing, jobId, runtimeId)
                : LlmJobRequest.fromPrompt(
                        jobId,
                        runtimeId,
                        exchange.getIn().getHeader("role", String.class),
                        body == null ? null : body.toString(),
                        exchange.getIn().getHeader("systemPrompt", String.class),
                        exchange.getIn().getHeader("responseSchema", String.class))
                        .normalized()
                        .ensureJsonOnlyResponse();

        ExecutionRuntimeDefinition runtime = runtimes.getRequired(request.requester());
        InferenceLaneDefinition lane = runtimes.laneRequired(runtime.laneId());
        request = withRouteMetadata(request, exchange, runtime, lane, globalModelFallback);
        exchange.setProperty("executionRuntime", runtime);
        exchange.setProperty("executionRuntimeId", runtime.id());
        exchange.setProperty("inferenceLane", lane);
        exchange.setProperty("modelId", selectedModel(runtime, lane, globalModelFallback));
        exchange.setProperty("laneRequestTopic", lane.requestTopic());
        exchange.setProperty("laneReplyTopic", lane.responseTopic());
        exchange.setProperty("llmPartitionKey", partitionKey(request, exchange, lane));
        applyLogPersona(exchange, personas, runtime.id());
        exchange.setProperty("llmJobId", request.jobId());
        exchange.setProperty("llmJob", LlmJobEnvelope.queued(request));
        exchange.getIn().setBody(exchange.getProperty("llmJob", LlmJobEnvelope.class));
    }

    public void restoreFrameworkMetadata(Exchange exchange) {
        restoreFrameworkMetadata(exchange, exchange.getProperty("llmJob", LlmJobEnvelope.class));
    }

    public void restoreFrameworkMetadata(Exchange exchange, LlmJobEnvelope job) {
        restoreFrameworkMetadataFromEnvelope(exchange, job);
    }

    public String partitionKey(LlmJobRequest request, Exchange exchange, InferenceLaneDefinition lane) {
        PartitionKeyStrategy strategy = lane == null ? PartitionKeyStrategy.AGENT_RUN : lane.partitionKeyStrategy();
        return switch (strategy) {
            case AGENT_RUN -> firstNonBlank(
                    agentRunId(exchange),
                    metadataValue(request, "agentRunId"),
                    missionId(exchange),
                    metadataValue(request, "missionId"),
                    String.valueOf(request.jobId()));
            case MISSION -> firstNonBlank(
                    missionId(exchange),
                    metadataValue(request, "missionId"),
                    agentRunId(exchange),
                    metadataValue(request, "agentRunId"),
                    String.valueOf(request.jobId()));
            case JOB -> String.valueOf(request.jobId());
        };
    }

    public String requestKafkaKey(Exchange exchange) {
        return exchange.getProperty("llmPartitionKey", String.class);
    }

    public UUID jobIdKafkaKey(Exchange exchange) {
        LlmJobEnvelope job = exchange.getProperty("llmJob", LlmJobEnvelope.class);
        UUID jobId = exchange.getProperty("llmJobId", UUID.class);
        if (jobId != null) {
            return jobId;
        }
        return job == null ? null : job.jobId();
    }

    private static LlmJobRequest withRouteMetadata(LlmJobRequest request, Exchange exchange, ExecutionRuntimeDefinition runtime, InferenceLaneDefinition lane, String globalModelFallback) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        if (request.metadata() != null) {
            metadata.putAll(request.metadata());
        }
        putIfPresent(metadata, "executionRuntime", runtime.id());
        putIfPresent(metadata, "inferenceLane", lane.id());
        putIfPresent(metadata, "model", selectedModel(runtime, lane, globalModelFallback));
        putIfPresent(metadata, "partitionKeyStrategy", lane.partitionKeyStrategy().name());
        putIfPresent(metadata, "personaId", exchange.getProperty("personaId", String.class));
        putIfPresent(metadata, "toolProfile", exchange.getProperty("toolProfile", String.class));
        return new LlmJobRequest(request.jobId(), request.requester(), request.role(), request.priority(), request.prompt(), request.systemPrompt(), request.responseSchema(), Map.copyOf(metadata), request.requestedAt());
    }

    private static String selectedModel(ExecutionRuntimeDefinition runtime, InferenceLaneDefinition lane, String globalModelFallback) {
        return LlmAgentFactory.selectedModel(runtime, lane, globalModelFallback);
    }

    private static void applyLogPersona(Exchange exchange, PersonaCatalog personas, String fallbackLabel) {
        String personaId = exchange.getProperty("personaId", String.class);
        if (personaId == null || personaId.isBlank()) {
            exchange.setProperty("agentLabel", fallbackLabel);
            exchange.removeProperty("agentLogColor");
            return;
        }
        Persona persona = personas.getRequired(personaId);
        exchange.setProperty("agentLabel", firstNonBlank(persona.label(), persona.name(), fallbackLabel));
        exchange.setProperty("agentLogColor", persona.color());
    }

    private static void restoreFrameworkMetadataFromEnvelope(Exchange exchange, LlmJobEnvelope job) {
        if (job == null || job.metadata() == null) {
            return;
        }
        job.metadata().forEach((key, value) -> {
            if (value != null) {
                exchange.setProperty(key, String.valueOf(value));
            }
        });
        putMetadataAlias(exchange, job, "executionRuntime", "executionRuntimeId");
        putMetadataAlias(exchange, job, "primitive", "currentPrimitive");
        putMetadataAlias(exchange, job, "primitiveId", "currentPrimitiveId");
    }

    private static void putMetadataAlias(Exchange exchange, LlmJobEnvelope job, String from, String to) {
        Object value = job.metadata().get(from);
        if (value != null) {
            exchange.setProperty(to, String.valueOf(value));
        }
    }

    private static LlmJobRequest normalize(LlmJobRequest request, UUID overrideJobId, String runtimeId) {
        LlmJobRequest normalized = overrideJobId == null ? request : request.withJobId(overrideJobId);
        if (normalized.requester() == null || normalized.requester().isBlank() || "anonymous".equals(normalized.requester())) {
            normalized = new LlmJobRequest(normalized.jobId(), runtimeId, normalized.role(), normalized.priority(), normalized.prompt(), normalized.systemPrompt(), normalized.responseSchema(), normalized.metadata(), normalized.requestedAt());
        }
        return normalized.normalized().ensureJsonOnlyResponse();
    }

    private static UUID readJobId(String rawJobId) {
        if (rawJobId == null || rawJobId.isBlank()) {
            return null;
        }
        return UUID.fromString(rawJobId);
    }

    private static String metadataValue(LlmJobRequest request, String key) {
        if (request == null || request.metadata() == null) {
            return null;
        }
        Object value = request.metadata().get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        return String.valueOf(value);
    }

    private static String agentRunId(Exchange exchange) {
        String explicit = exchange.getProperty("agentRunId", String.class);
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        return run == null ? null : run.id();
    }

    private static String missionId(Exchange exchange) {
        String explicit = exchange.getProperty("missionId", String.class);
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        Mission mission = exchange.getProperty("mission", Mission.class);
        return mission == null ? null : mission.id();
    }

    private static void putIfPresent(Map<String, Object> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
