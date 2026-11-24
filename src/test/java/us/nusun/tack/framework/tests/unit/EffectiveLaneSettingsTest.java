package us.nusun.tack.framework.tests.unit;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;
import us.nusun.tack.example.runtime.HighPassLane;
import us.nusun.tack.example.runtime.LowPassLane;
import us.nusun.tack.framework.config.EffectiveLaneSettings;
import us.nusun.tack.framework.config.LaneConfiguration;
import us.nusun.tack.framework.config.ModelConfiguration;
import us.nusun.tack.framework.model.PartitionKeyStrategy;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EffectiveLaneSettingsTest {
    @Test
    void laneDeclarationsKeepTheirOwnTopicAndPartitionFallbacks() {
        var config = new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(Map.of(
                        "llm.inference-lane.low.model", "low-model",
                        "llm.inference-lane.high.model", "high-model"), "lanes", 100))
                .withMapping(LaneConfiguration.class)
                .build().getConfigMapping(LaneConfiguration.class);

        var low = new LowPassLane(config).inferenceLane();
        assertEquals("low-pass", low.id());
        assertEquals("llm.requests.low", low.requestTopic());
        assertEquals("llm.replies.low", low.responseTopic());
        assertEquals(1, low.parallelism());
        assertEquals(PartitionKeyStrategy.AGENT_RUN, low.partitionKeyStrategy());

        var high = new HighPassLane(config).inferenceLane();
        assertEquals("high-pass", high.id());
        assertEquals("llm.requests.high", high.requestTopic());
        assertEquals("llm.replies.high", high.responseTopic());
        assertEquals(1, high.parallelism());
        assertEquals(PartitionKeyStrategy.JOB, high.partitionKeyStrategy());
    }

    @Test
    void retainsEffectiveDefaultsAndLaneEnvironmentAliases() throws Exception {
        var properties = new PropertiesConfigSource(getClass().getResource("/application.properties"));
        var config = new SmallRyeConfigBuilder()
                .addDefaultInterceptors()
                .withSources(properties, new PropertiesConfigSource(Map.of(
                        "LLM_MODEL_DEFAULT_OPENAI_MODEL", "global-model",
                        "LLM_MODEL_DEFAULT_OPENAI_TEMPERATURE", "0.2",
                        "LLM_HIGH_API_KEY", "test-lane-credential",
                        "LLM_HIGH_MODEL", "high-model",
                        "LLM_HIGH_ENDPOINT", "http://inference.example/v1",
                        "LLM_HIGH_PARALLELISM", "4",
                        "LLM_HIGH_TOOL_MEMORY_MAX_TOKENS", "7000"), "aliases", 400))
                .withMapping(ModelConfiguration.class)
                .withMapping(LaneConfiguration.class)
                .build();
        var defaults = config.getConfigMapping(ModelConfiguration.class);
        var lanes = config.getConfigMapping(LaneConfiguration.class);
        var lowLane = new LowPassLane(lanes).inferenceLane();
        var highLane = new HighPassLane(lanes).inferenceLane();

        var low = EffectiveLaneSettings.resolve(lowLane, defaults, lanes, config);
        var high = EffectiveLaneSettings.resolve(highLane, defaults, lanes, config);
        assertEquals("global-model", low.model());
        assertEquals(0.2, low.temperature());
        assertEquals(50000, low.toolPass().memoryTokens());
        assertEquals(50, low.toolPass().maxToolCalls());
        assertEquals(PartitionKeyStrategy.AGENT_RUN, lowLane.partitionKeyStrategy());
        assertEquals("high-model", high.model());
        assertEquals("http://inference.example/v1", high.baseUrl());
        assertEquals(4, highLane.parallelism());
        assertEquals(0.7, high.temperature());
        assertEquals("test-lane-credential", high.apiKey());
        assertEquals(7000, high.toolPass().memoryTokens());
        assertEquals(PartitionKeyStrategy.JOB, highLane.partitionKeyStrategy());
    }
}
