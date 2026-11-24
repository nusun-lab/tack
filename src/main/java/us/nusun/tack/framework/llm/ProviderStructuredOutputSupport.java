package us.nusun.tack.framework.llm;

import org.apache.camel.Exchange;
import us.nusun.tack.framework.model.LlmJobEnvelope;


public final class ProviderStructuredOutputSupport {
    public static final String FORCE_PROVIDER_SCHEMA_PROPERTY = "llmForceProviderSchema";
    private static final String PROVIDER_SCHEMA_ENABLED_PLACEHOLDER =
            "{{llm.model-default.structured-output.provider-schema-enabled:false}}";

    private ProviderStructuredOutputSupport() {
    }

    public static boolean shouldApplyProviderSchema(Exchange exchange, LlmJobEnvelope job) {
        return hasResponseSchema(job) && providerSchemaEnabled(exchange, job);
    }

    public static boolean hasResponseSchema(LlmJobEnvelope job) {
        return job != null && job.responseSchema() != null && !job.responseSchema().isBlank();
    }

    public static boolean providerSchemaEnabled(Exchange exchange, LlmJobEnvelope job) {
        if (forceProviderSchema(exchange) || Boolean.TRUE.equals(LlmExecutionContext.from(job).forceProviderSchema())) {
            return true;
        }
        if (exchange == null || exchange.getContext() == null) {
            return false;
        }
        return Boolean.parseBoolean(exchange.getContext().resolvePropertyPlaceholders(PROVIDER_SCHEMA_ENABLED_PLACEHOLDER));
    }

    private static boolean forceProviderSchema(Exchange exchange) {
        if (exchange == null) {
            return false;
        }
        Object value = exchange.getProperty(FORCE_PROVIDER_SCHEMA_PROPERTY);
        if (value == null) {
            value = exchange.getIn().getHeader(FORCE_PROVIDER_SCHEMA_PROPERTY);
        }
        return booleanValue(value);
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }
}
