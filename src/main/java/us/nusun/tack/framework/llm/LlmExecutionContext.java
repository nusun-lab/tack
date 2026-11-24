package us.nusun.tack.framework.llm;

import org.apache.camel.Exchange;
import us.nusun.tack.framework.model.InferenceLaneDefinition;
import us.nusun.tack.framework.model.LlmJobEnvelope;
import us.nusun.tack.framework.model.LlmPassType;
import us.nusun.tack.framework.model.Mission;
import us.nusun.tack.framework.model.PartitionKeyStrategy;
import us.nusun.tack.framework.state.AgentRun;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static us.nusun.tack.framework.model.FrameworkText.firstNonBlank;
import static us.nusun.tack.framework.model.FrameworkText.putIfNonBlank;
import static us.nusun.tack.framework.model.FrameworkText.putIfPresent;

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
        Boolean forceProviderSchema
) {
    public static final String PROPERTY = "llmExecutionContext";
    private static final String FORCE_SCHEMA = "llmForceProviderSchema";
    private static final String CAPACITY = "agentRunCapacityMaximum";

    public static LlmExecutionContext from(LlmJobEnvelope job) {
        Map<String, Object> fields = job == null ? Map.of() : job.metadata();
        return new LlmExecutionContext(job == null ? null : job.jobId(),
                firstNonBlank(job == null ? null : job.laneId(), text(fields, "laneId"), text(fields, "inferenceLane")),
                job != null && job.passType() != null ? job.passType() : enumValue(LlmPassType.class, fields.get("passType")),
                text(fields, "model"), enumValue(PartitionKeyStrategy.class, fields.get("partitionKeyStrategy")),
                text(fields, "missionId"), text(fields, "agentRunId"), text(fields, "personaId"),
                text(fields, "toolProfile"), text(fields, "sandboxName"), text(fields, "primitive"),
                text(fields, "primitiveId"), text(fields, "primitivePrompt"), integer(fields.get(CAPACITY)),
                bool(fields.get(FORCE_SCHEMA)));
    }

    public static LlmExecutionContext capture(Exchange exchange, LlmJobEnvelope job,
                                              InferenceLaneDefinition lane, String model) {
        LlmExecutionContext fallback = from(job);
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        Mission mission = exchange.getProperty("mission", Mission.class);
        Boolean force = bool(propertyOrHeader(exchange, FORCE_SCHEMA));
        Integer capacity = integer(propertyOrHeader(exchange, CAPACITY));
        return new LlmExecutionContext(job.jobId(), job.laneId(), job.passType(), firstNonBlank(model, fallback.model()),
                lane == null ? fallback.partition() : lane.partitionKeyStrategy(),
                firstNonBlank(exchange.getProperty("missionId", String.class), mission == null ? null : mission.id(), fallback.missionId()),
                firstNonBlank(exchange.getProperty("agentRunId", String.class), run == null ? null : run.id(), fallback.agentRunId()),
                firstNonBlank(exchange.getProperty("personaId", String.class), fallback.personaId(), run == null ? null : run.personaId()),
                firstNonBlank(exchange.getProperty("toolProfile", String.class), fallback.toolProfile()),
                firstNonBlank(exchange.getProperty("sandboxName", String.class), fallback.sandboxName()),
                firstNonBlank(fallback.primitive(), run == null ? null : run.currentPrimitive()),
                firstNonBlank(fallback.primitiveId(), run == null ? null : run.currentId()),
                firstNonBlank(fallback.primitivePrompt(), exchange.getProperty("actPrompt", String.class),
                        exchange.getProperty("decisionPrompt", String.class), exchange.getProperty("createPrompt", String.class)),
                capacity == null ? fallback.capacityMaximum() : capacity,
                force == null ? fallback.forceProviderSchema() : force);
    }

    public static LlmExecutionContext on(Exchange exchange) {
        LlmExecutionContext context = exchange.getProperty(PROPERTY, LlmExecutionContext.class);
        return context == null ? from(exchange.getProperty("llmJob", LlmJobEnvelope.class)) : context;
    }

    public Map<String, Object> wireMetadata(Map<String, Object> extensions) {
        Map<String, Object> fields = new LinkedHashMap<>(extensions);
        putIfNonBlank(fields, "laneId", laneId);
        putIfPresent(fields, "passType", passType == null ? null : passType.name());
        putIfNonBlank(fields, "model", model);
        putIfPresent(fields, "partitionKeyStrategy", partition == null ? null : partition.name());
        putIfNonBlank(fields, "missionId", missionId);
        putIfNonBlank(fields, "agentRunId", agentRunId);
        putIfNonBlank(fields, "personaId", personaId);
        putIfNonBlank(fields, "toolProfile", toolProfile);
        putIfNonBlank(fields, "sandboxName", sandboxName);
        putIfNonBlank(fields, "primitive", primitive);
        putIfNonBlank(fields, "primitiveId", primitiveId);
        putIfNonBlank(fields, "primitivePrompt", primitivePrompt);
        putIfPresent(fields, CAPACITY, capacityMaximum);
        putIfPresent(fields, FORCE_SCHEMA, forceProviderSchema);
        return fields;
    }

    public void exposeToCamel(Exchange exchange) {
        exchange.setProperty(PROPERTY, this);
        wireMetadata(Map.of()).forEach(exchange::setProperty);
        exchange.setProperty("llmJobId", jobId);
        exchange.setProperty("passType", passType);
    }

    private static String text(Map<String, Object> fields, String name) {
        return fields.get(name) instanceof String text ? text : null;
    }

    static <T extends Enum<T>> T enumValue(Class<T> type, Object value) {
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        return value instanceof String text && !text.isBlank()
                ? Enum.valueOf(type, text.strip().toUpperCase(Locale.ROOT)) : null;
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value instanceof String text && !text.isBlank() ? Integer.valueOf(text.strip()) : null;
    }

    private static Boolean bool(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        return value instanceof String text ? Boolean.valueOf(text) : null;
    }

    private static Object propertyOrHeader(Exchange exchange, String key) {
        Object value = exchange.getProperty(key);
        return value != null ? value : exchange.getMessage().getHeader(key);
    }
}
