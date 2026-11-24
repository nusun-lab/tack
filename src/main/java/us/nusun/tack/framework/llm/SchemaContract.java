package us.nusun.tack.framework.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import org.apache.camel.CamelContext;
import org.apache.camel.support.ResourceHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/** One normalized schema for the prompt, the provider and local validation. */
public final class SchemaContract {
    private final JsonNode schema;

    private SchemaContract(JsonNode schema) {
        this.schema = schema;
    }

    public static SchemaContract resolve(CamelContext camel, ObjectMapper mapper, String input) throws IOException {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Response schema is required");
        }
        String source = input.strip();
        if (!inline(source)) {
            source = camel.resolvePropertyPlaceholders(source).strip();
        }
        String content = source;
        if (!inline(source)) {
            try (var stream = ResourceHelper.resolveMandatoryResourceAsInputStream(camel, source)) {
                content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        JsonNode schema;
        try {
            schema = readJson(mapper, content);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Response schema is not valid JSON", failure);
        }
        if (schema == null || !(schema.isObject() || schema.isBoolean())) {
            throw new IllegalArgumentException("Response schema must be a JSON object or boolean");
        }
        return new SchemaContract(schema);
    }

    private static boolean inline(String source) {
        return (source.startsWith("{") && !source.startsWith("{{")) || source.startsWith("[")
                || "true".equals(source) || "false".equals(source);
    }

    static JsonNode readJson(ObjectMapper mapper, String content) throws JsonProcessingException {
        return mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(content);
    }

    public String json() {
        return schema.toString();
    }

    public ResponseFormat providerFormat() {
        return ResponseFormat.builder().type(ResponseFormatType.JSON)
                .jsonSchema(JsonSchema.builder().name("camel_schema").rootElement(JsonRawSchema.from(json())).build())
                .build();
    }

    public void validate(JsonNode response) {
        var errors = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(schema).validate(response);
        if (!errors.isEmpty()) {
            String details = errors.stream().map(Error::getMessage).sorted().collect(Collectors.joining("; "));
            throw new IllegalArgumentException("LLM response schema validation failed: " + details);
        }
    }
}
