package us.nusun.tack.framework.inference.lane;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;

class EffectiveLaneSettingsTest {
    @Test
    void resolvesGlobalDefaultsAndSyntheticLaneOverrides() {
        var config = new SmallRyeConfigBuilder()
                .addDefaultInterceptors()
                .withSources(new PropertiesConfigSource(Map.ofEntries(
                        entry("llm.model-default.openai.base-url", "http://default-model.example/v1"),
                        entry("llm.model-default.openai.api-key", "global-test-key"),
                        entry("llm.model-default.openai.model", "default-model"),
                        entry("llm.model-default.openai.temperature", "0.3"),
                        entry("llm.model-default.openai.top-p", "0.9"),
                        entry("llm.model-default.openai.presence-penalty", "0.1"),
                        entry("llm.model-default.openai.top-k", "24"),
                        entry("llm.model-default.openai.min-p", "0.05"),
                        entry("llm.model-default.openai.repetition-penalty", "1.1"),
                        entry("llm.model-default.openai.max-tokens", "2048"),
                        entry("llm.model-default.openai.timeout-ms", "10000"),
                        entry("llm.model-default.openai.thinking-off.enabled", "true"),
                        entry("llm.inference-lane.review.api-key", "${TEST_REVIEW_API_KEY}"),
                        entry("llm.inference-lane.review.temperature", "0.6"),
                        entry("llm.inference-lane.review.tool-memory.max-tokens", "7000"),
                        entry("test.credentials.import", "import-test-key")),
                        "framework-test-settings", 100),
                        new PropertiesConfigSource(Map.of("TEST_REVIEW_API_KEY", "review-test-key"), "test-alias", 200))
                .withMapping(ModelConfiguration.class)
                .withMapping(LaneConfiguration.class)
                .build();
        var defaults = config.getConfigMapping(ModelConfiguration.class);
        var lanes = config.getConfigMapping(LaneConfiguration.class);

        var inherited = EffectiveLaneSettings.resolve(lane("baseline").build(), defaults, lanes, config);
        assertEquals("default-model", inherited.model());
        assertEquals("http://default-model.example/v1", inherited.baseUrl());
        assertEquals("global-test-key", inherited.apiKey());
        assertEquals(0.3, inherited.temperature());
        // No tool-memory or round-trip settings were supplied for this lane or globally.
        assertEquals(12000, inherited.toolPass().memoryTokens());
        assertEquals(4, inherited.toolPass().maxToolCalls());

        var reviewLane = lane("review").configName("review")
                .model("review-model").endpoint("http://review-model.example/v1").build();
        var review = EffectiveLaneSettings.resolve(reviewLane, defaults, lanes, config);
        assertEquals("review-model", review.model());
        assertEquals("http://review-model.example/v1", review.baseUrl());
        assertEquals(0.6, review.temperature());
        assertEquals("review-test-key", review.apiKey());
        assertEquals(7000, review.toolPass().memoryTokens());
        assertEquals(inherited.toolPass().compactionThreshold(), review.toolPass().compactionThreshold());

        var importLane = lane("import").apiKeyConfigKey("test.credentials.import").build();
        assertEquals("import-test-key", EffectiveLaneSettings.resolve(importLane, defaults, lanes, config).apiKey());
    }

    private static InferenceLaneDefinition.Builder lane(String id) {
        return InferenceLaneDefinition.builder(id)
                .requestTopic("test.requests." + id)
                .responseTopic("test.replies." + id);
    }
}
