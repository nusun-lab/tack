package us.nusun.tack.framework.llm;

import static us.nusun.tack.framework.model.FrameworkText.firstNonBlank;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.tack.framework.inventory.InferenceLaneRegistry;
import us.nusun.tack.framework.inventory.PersonaCatalog;
import us.nusun.tack.framework.model.InferenceLaneDefinition;
import us.nusun.tack.framework.model.LlmJobEnvelope;
import us.nusun.tack.framework.model.LlmPassType;
import us.nusun.tack.framework.model.Mission;
import us.nusun.tack.framework.model.PartitionKeyStrategy;
import us.nusun.tack.framework.model.Persona;
import us.nusun.tack.framework.model.RoutineDefinition;
import us.nusun.tack.framework.state.AgentRun;

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
        LlmPassType headerPassType = firstPassType(
                exchange.getIn().getHeader("passType"),
                exchange.getIn().getHeader("llmPassType"));
        LlmPassType propertyPassType = firstPassType(
                exchange.getProperty("passType"),
                exchange.getProperty("llmPassType"));

        LlmJobEnvelope request = body instanceof LlmJobEnvelope existing
                ? normalize(
                        existing,
                        jobId,
                        firstNonBlank(headerLaneId, blank(existing.laneId()) ? defaultLaneId : null),
                        headerPassType)
                : LlmJobEnvelope.fromPrompt(
                        jobId,
                        firstNonBlank(headerLaneId, propertyLaneId, defaultLaneId),
                        firstPassType(headerPassType, propertyPassType, LlmPassType.SYNTHESIS),
                        exchange.getIn().getHeader("requester", String.class),
                        exchange.getIn().getHeader("role", String.class),
                        body == null ? null : body.toString(),
                        exchange.getIn().getHeader("systemPrompt", String.class),
                        exchange.getIn().getHeader("responseSchema", String.class))
                        .normalized()
                        .ensureJsonOnlyResponse();

        InferenceLaneDefinition lane = lanes.getRequired(request.laneId());
        String model = selectedModel(lane, globalModelFallback);
        LlmExecutionContext context = LlmExecutionContext.capture(exchange, request, lane, model);
        LlmJobEnvelope queued = LlmJobEnvelope.queued(request.withMetadata(context.wireMetadata(request.metadata())));
        context.exposeToCamel(exchange);
        exchange.setProperty("inferenceLane", lane);
        exchange.setProperty("modelId", model);
        exchange.setProperty("laneRequestTopic", lane.requestTopic());
        exchange.setProperty("laneReplyTopic", lane.responseTopic());
        exchange.setProperty("llmPartitionKey", partitionKey(request, exchange, lane));
        applyLogPersona(exchange, personas, request.requester());
        exchange.setProperty("llmJob", queued);
        exchange.getIn().setBody(queued);
    }

    public void restoreFrameworkMetadata(Exchange exchange) {
        restoreFrameworkMetadata(exchange, exchange.getProperty("llmJob", LlmJobEnvelope.class));
    }

    public void restoreFrameworkMetadata(Exchange exchange, LlmJobEnvelope job) {
        if (job != null) {
            LlmExecutionContext.from(job).exposeToCamel(exchange);
        }
        applyLogPersona(exchange, personas, job == null ? null : job.requester());
    }

    public String partitionKey(LlmJobEnvelope request, Exchange exchange, InferenceLaneDefinition lane) {
        PartitionKeyStrategy strategy = lane == null ? PartitionKeyStrategy.AGENT_RUN : lane.partitionKeyStrategy();
        LlmExecutionContext context = LlmExecutionContext.from(request);
        return switch (strategy) {
            case AGENT_RUN -> firstNonBlank(
                    agentRunId(exchange),
                    context.agentRunId(),
                    missionId(exchange),
                    context.missionId(),
                    String.valueOf(request.jobId()));
            case MISSION -> firstNonBlank(
                    missionId(exchange),
                    context.missionId(),
                    agentRunId(exchange),
                    context.agentRunId(),
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

    private static LlmJobEnvelope normalize(LlmJobEnvelope request, UUID overrideJobId, String laneId, LlmPassType passType) {
        LlmJobEnvelope normalized = overrideJobId == null ? request : request.withJobId(overrideJobId);
        normalized = normalized.withLaneAndPass(laneId, passType);
        return normalized.normalized().ensureJsonOnlyResponse();
    }

    private static LlmPassType firstPassType(Object... values) {
        for (Object value : values) {
            LlmPassType parsed = parsePassType(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private static LlmPassType parsePassType(Object value) {
        return LlmExecutionContext.enumValue(LlmPassType.class, value);
    }

    private static UUID readJobId(String rawJobId) {
        if (rawJobId == null || rawJobId.isBlank()) {
            return null;
        }
        return UUID.fromString(rawJobId);
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
