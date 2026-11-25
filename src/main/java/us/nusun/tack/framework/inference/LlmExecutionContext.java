package us.nusun.tack.framework.inference;

import org.apache.camel.Exchange;
import us.nusun.tack.framework.inference.lane.PartitionKeyStrategy;
import us.nusun.tack.framework.persona.Persona;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static us.nusun.tack.framework.FrameworkText.firstNonBlank;
import static us.nusun.tack.framework.FrameworkText.putIfNonBlank;
import static us.nusun.tack.framework.FrameworkText.putIfPresent;
import static us.nusun.tack.framework.run.AgentRunAdmissionService.CAPACITY_MAXIMUM_PROPERTY;

public record LlmExecutionContext(
        UUID jobId,
        String laneId,
        LlmPassType passType,
        String model,
        PartitionKeyStrategy partition,
        String missionId,
        String agentRunId,
        String personaId,
        String toolProfile,
        String sandboxName,
        String primitive,
        String primitiveId,
        String primitivePrompt,
        Integer capacityMaximum,
        boolean providerSchemaEnabled,
        String logLabel,
        String logColor
) {
    public static final String PROPERTY = "llmExecutionContext";
    public static final String FORCE_SCHEMA = "llmForceProviderSchema";

    static LlmExecutionContext restore(LlmJobEnvelope job, Persona persona, String defaultModel, boolean providerSchemaDefault) {
        Map<String, Object> fields = job.metadata();
        String partition = text(fields, "partitionKeyStrategy");
        Boolean force = flag(fields.get(FORCE_SCHEMA));
        return new LlmExecutionContext(job.jobId(), job.laneId(), job.passType(), firstNonBlank(text(fields, "model"), defaultModel),
                partition == null ? null : PartitionKeyStrategy.valueOf(partition),
                text(fields, "missionId"), text(fields, "agentRunId"), text(fields, "personaId"),
                text(fields, "toolProfile"), text(fields, "sandboxName"), text(fields, "primitive"),
                text(fields, "primitiveId"), text(fields, "primitivePrompt"), integer(fields.get(CAPACITY_MAXIMUM_PROPERTY)),
                providerSchemaDefault || Boolean.TRUE.equals(force), label(persona, job.requester()),
                persona == null ? null : persona.color());
    }

    public static LlmExecutionContext on(Exchange exchange) {
        return exchange.getProperty(PROPERTY, LlmExecutionContext.class);
    }

    public static LlmExecutionContext require(Exchange exchange) {
        LlmExecutionContext context = on(exchange);
        if (context == null) {
            throw new IllegalStateException("LLM execution context is missing");
        }
        return context;
    }

    String partitionKey() {
        String jobKey = jobId.toString();
        return switch (partition) {
            case AGENT_RUN -> firstNonBlank(agentRunId, missionId, jobKey);
            case MISSION -> firstNonBlank(missionId, agentRunId, jobKey);
            case JOB -> jobKey;
        };
    }

    Map<String, Object> wireMetadata(Map<String, Object> extensions, Boolean forceProviderSchema) {
        Map<String, Object> fields = new LinkedHashMap<>(extensions);
        fields.put("laneId", laneId);
        fields.put("passType", passType.name());
        fields.put("partitionKeyStrategy", partition.name());
        putIfNonBlank(fields, "model", model);
        putIfNonBlank(fields, "missionId", missionId);
        putIfNonBlank(fields, "agentRunId", agentRunId);
        putIfNonBlank(fields, "personaId", personaId);
        putIfNonBlank(fields, "toolProfile", toolProfile);
        putIfNonBlank(fields, "sandboxName", sandboxName);
        putIfNonBlank(fields, "primitive", primitive);
        putIfNonBlank(fields, "primitiveId", primitiveId);
        putIfNonBlank(fields, "primitivePrompt", primitivePrompt);
        putIfPresent(fields, CAPACITY_MAXIMUM_PROPERTY, capacityMaximum);
        putIfPresent(fields, FORCE_SCHEMA, forceProviderSchema);
        return fields;
    }

    static String label(Persona persona, String requester) {
        return persona == null ? firstNonBlank(requester, "LLM")
                : firstNonBlank(persona.label(), persona.name(), requester, "LLM");
    }

    static String text(Map<String, Object> fields, String name) {
        return fields.get(name) instanceof String text ? text : null;
    }

    static Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value instanceof String text && !text.isBlank() ? Integer.valueOf(text.strip()) : null;
    }

    static Boolean flag(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        return value instanceof String text ? Boolean.valueOf(text) : null;
    }

    static Object propertyOrHeader(Exchange exchange, String key) {
        Object value = exchange.getProperty(key);
        return value != null ? value : exchange.getMessage().getHeader(key);
    }
}
