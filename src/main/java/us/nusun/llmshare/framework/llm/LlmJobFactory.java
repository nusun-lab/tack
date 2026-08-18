package us.nusun.llmshare.framework.llm;

import static us.nusun.llmshare.framework.model.FrameworkText.firstNonBlank;
import static us.nusun.llmshare.framework.model.FrameworkText.putIfNonBlank;
import static us.nusun.llmshare.framework.model.FrameworkText.putIfPresent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.framework.inventory.InferenceLaneRegistry;
import us.nusun.llmshare.framework.inventory.PersonaCatalog;
import us.nusun.llmshare.framework.model.InferenceLaneDefinition;
import us.nusun.llmshare.framework.model.LlmJobEnvelope;
import us.nusun.llmshare.framework.model.LlmJobRequest;
import us.nusun.llmshare.framework.model.Mission;
import us.nusun.llmshare.framework.model.PartitionKeyStrategy;
import us.nusun.llmshare.framework.model.Persona;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.state.AgentRun;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class LlmJobFactory {
    @Inject InferenceLaneRegistry lanes;
    @Inject PersonaCatalog personas;
    @ConfigProperty(name = "agent.framework.default-lane", defaultValue = "low-pass")
    String defaultLane = RoutineDefinition.FRAMEWORK_DEFAULT_LANE;

    public LlmJobFactory() {
    }

    public LlmJobFactory(InferenceLaneRegistry lanes, PersonaCatalog personas) {
        this.lanes = lanes;
        this.personas = personas;
    }

    public void prepare(Exchange exchange) {
        prepareWithCatalogs(
                exchange,
                lanes,
                personas,
                firstNonBlank(defaultLane, RoutineDefinition.FRAMEWORK_DEFAULT_LANE),
                globalModelFallback(exchange));
    }

    private void prepareWithCatalogs(
            Exchange exchange,
            InferenceLaneRegistry lanes,
            PersonaCatalog personas,
            String defaultLaneId,
            String globalModelFallback) {
        Object body = exchange.getIn().getBody();
        UUID overrideJobId = exchange.getProperty("llmJobId", UUID.class);
        UUID headerJobId = readJobId(exchange.getIn().getHeader("jobId", String.class));
        UUID jobId = overrideJobId != null ? overrideJobId : headerJobId;
        String headerLaneId = firstNonBlank(
                exchange.getIn().getHeader("lane", String.class),
                exchange.getIn().getHeader("laneId", String.class));
        String propertyLaneId = firstNonBlank(
                exchange.getProperty("laneId", String.class),
                exchange.getProperty("lane", String.class));
        LlmJobRequest request = body instanceof LlmJobRequest existing
                ? normalize(
                        existing,
                        jobId,
                        firstNonBlank(headerLaneId, blank(existing.laneId()) ? defaultLaneId : null))
                : LlmJobRequest.fromPrompt(
                        jobId,
                        firstNonBlank(headerLaneId, propertyLaneId, defaultLaneId),
                        exchange.getIn().getHeader("requester", String.class),
                        exchange.getIn().getHeader("role", String.class),
                        body == null ? null : body.toString(),
                        exchange.getIn().getHeader("systemPrompt", String.class),
                        exchange.getIn().getHeader("responseSchema", String.class))
                        .normalized()
                        .ensureJsonOnlyResponse();

        InferenceLaneDefinition lane = lanes.getRequired(request.laneId());
        request = withRouteMetadata(request, exchange, lane, globalModelFallback);
        exchange.setProperty("laneId", lane.id());
        exchange.setProperty("inferenceLane", lane);
        exchange.setProperty("modelId", selectedModel(lane, globalModelFallback));
        exchange.setProperty("laneRequestTopic", lane.requestTopic());
        exchange.setProperty("laneReplyTopic", lane.responseTopic());
        exchange.setProperty("llmPartitionKey", partitionKey(request, exchange, lane));
        applyLogPersona(exchange, personas, request.requester());
        exchange.setProperty("llmJobId", request.jobId());
        exchange.setProperty("llmJob", LlmJobEnvelope.queued(request));
        exchange.getIn().setBody(exchange.getProperty("llmJob", LlmJobEnvelope.class));
    }

    public void restoreFrameworkMetadata(Exchange exchange) {
        restoreFrameworkMetadata(exchange, exchange.getProperty("llmJob", LlmJobEnvelope.class));
    }

    public void restoreFrameworkMetadata(Exchange exchange, LlmJobEnvelope job) {
        restoreFrameworkMetadataFromEnvelope(exchange, job);
        applyLogPersona(exchange, personas, job == null ? null : job.requester());
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

    private static LlmJobRequest withRouteMetadata(LlmJobRequest request, Exchange exchange, InferenceLaneDefinition lane, String globalModelFallback) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        if (request.metadata() != null) {
            metadata.putAll(request.metadata());
        }
        putIfNonBlank(metadata, "laneId", lane.id());
        putIfNonBlank(metadata, "model", selectedModel(lane, globalModelFallback));
        putIfNonBlank(metadata, "partitionKeyStrategy", lane.partitionKeyStrategy().name());
        putIfNonBlank(metadata, "personaId", exchange.getProperty("personaId", String.class));
        putIfNonBlank(metadata, "toolProfile", exchange.getProperty("toolProfile", String.class));
        putIfPresent(metadata, ProviderStructuredOutputSupport.FORCE_PROVIDER_SCHEMA_PROPERTY,
                firstPresent(exchange.getProperty(ProviderStructuredOutputSupport.FORCE_PROVIDER_SCHEMA_PROPERTY),
                        exchange.getIn().getHeader(ProviderStructuredOutputSupport.FORCE_PROVIDER_SCHEMA_PROPERTY)));
        return new LlmJobRequest(request.jobId(), request.laneId(), request.requester(), request.role(), request.priority(), request.prompt(), request.systemPrompt(), request.responseSchema(), Map.copyOf(metadata), request.requestedAt());
    }

    private static String selectedModel(InferenceLaneDefinition lane, String globalModelFallback) {
        return LlmAgentFactory.selectedModel(lane, globalModelFallback);
    }

    private static void applyLogPersona(Exchange exchange, PersonaCatalog personas, String fallbackLabel) {
        String personaId = exchange.getProperty("personaId", String.class);
        if (personaId == null || personaId.isBlank() || personas == null) {
            exchange.setProperty("agentLabel", firstNonBlank(fallbackLabel, "LLM"));
            exchange.removeProperty("agentLogColor");
            return;
        }
        Persona persona = personas.getRequired(personaId);
        exchange.setProperty("agentLabel", firstNonBlank(persona.label(), persona.name(), fallbackLabel));
        exchange.setProperty("agentLogColor", persona.color());
    }

    private static void restoreFrameworkMetadataFromEnvelope(Exchange exchange, LlmJobEnvelope job) {
        if (job == null) {
            return;
        }
        exchange.setProperty("llmJobId", job.jobId());
        exchange.setProperty("laneId", job.laneId());
        if (job.metadata() == null) {
            return;
        }
        job.metadata().forEach((key, value) -> {
            if (value != null) {
                exchange.setProperty(key, String.valueOf(value));
            }
        });
    }

    private static LlmJobRequest normalize(LlmJobRequest request, UUID overrideJobId, String laneId) {
        LlmJobRequest normalized = overrideJobId == null ? request : request.withJobId(overrideJobId);
        return normalized.withLane(laneId).normalized().ensureJsonOnlyResponse();
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


    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static Object firstPresent(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
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
