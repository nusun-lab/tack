package us.nusun.llmshare.framework.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.support.ResourceHelper;
import us.nusun.llmshare.framework.model.LlmJobEnvelope;

import java.io.IOException;
import java.io.InputStream;

public final class ProviderStructuredOutputSupport {
    public static final String FORCE_PROVIDER_SCHEMA_PROPERTY = "llmForceProviderSchema";
    private static final String PROVIDER_SCHEMA_ENABLED_PLACEHOLDER =
            "{{llm.model-default.structured-output.provider-schema-enabled:false}}";
    private static final String SCHEMA_NAME = "camel_schema";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ProviderStructuredOutputSupport() {
    }

    public static boolean shouldApplyProviderSchema(Exchange exchange, LlmJobEnvelope job) {
        return hasResponseSchema(job) && providerSchemaEnabled(exchange, job);
    }

    public static boolean hasResponseSchema(LlmJobEnvelope job) {
        return job != null && job.responseSchema() != null && !job.responseSchema().isBlank();
    }

    public static boolean providerSchemaEnabled(Exchange exchange, LlmJobEnvelope job) {
        if (forceProviderSchema(exchange) || forceProviderSchema(job)) {
            return true;
        }
        if (exchange == null || exchange.getContext() == null) {
            return false;
        }
        return Boolean.parseBoolean(exchange.getContext().resolvePropertyPlaceholders(PROVIDER_SCHEMA_ENABLED_PLACEHOLDER));
    }

    public static ResponseFormat responseFormat(CamelContext camelContext, String jsonSchema) throws IOException {
        if (camelContext == null) {
            throw new IllegalArgumentException("Camel context is required to resolve response schema resources");
        }
        if (jsonSchema == null || jsonSchema.isBlank()) {
            throw new IllegalArgumentException("Response schema is required for provider structured output");
        }

        String resolved = camelContext.resolvePropertyPlaceholders(jsonSchema);
        String content = resolveResourceContent(camelContext, resolved);
        if (content != null) {
            resolved = content;
        }

        try {
            OBJECT_MAPPER.readTree(resolved);
        } catch (Exception e) {
            throw new IllegalArgumentException("responseSchema does not contain valid JSON", e);
        }

        JsonSchema schema = JsonSchema.builder()
                .name(SCHEMA_NAME)
                .rootElement(JsonRawSchema.from(resolved))
                .build();
        return ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(schema)
                .build();
    }

    private static String resolveResourceContent(CamelContext camelContext, String property) throws IOException {
        if (ResourceHelper.hasScheme(property)) {
            try (InputStream is = ResourceHelper.resolveMandatoryResourceAsInputStream(camelContext, property)) {
                return camelContext.getTypeConverter().convertTo(String.class, is);
            }
        }
        try (InputStream is = ResourceHelper.resolveResourceAsInputStream(camelContext, property)) {
            if (is != null) {
                return camelContext.getTypeConverter().convertTo(String.class, is);
            }
        } catch (Exception ignored) {
            // Not a resolvable resource URI. Treat the value as inline JSON schema content.
        }
        return null;
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

    private static boolean forceProviderSchema(LlmJobEnvelope job) {
        if (job == null || job.metadata() == null) {
            return false;
        }
        return booleanValue(job.metadata().get(FORCE_PROVIDER_SCHEMA_PROPERTY));
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }
}
