package us.nusun.tack.framework.tests.unit;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;
import us.nusun.tack.example.runtime.SoftwareTeamLanes;
import us.nusun.tack.framework.config.EffectiveLaneSettings;
import us.nusun.tack.framework.config.LaneConfiguration;
import us.nusun.tack.framework.config.ModelConfiguration;
import us.nusun.tack.framework.model.PartitionKeyStrategy;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EffectiveLaneSettingsTest {
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
                        "LLM_HIGH_TOOL_MEMORY_MAX_TOKENS", "7000"), "aliases", 400))
                .withMapping(ModelConfiguration.class)
                .withMapping(LaneConfiguration.class)
                .build();
        var defaults = config.getConfigMapping(ModelConfiguration.class);
        var lanes = config.getConfigMapping(LaneConfiguration.class);
        var definitions = new SoftwareTeamLanes(lanes).inferenceLanes();

        var low = EffectiveLaneSettings.resolve(definitions.getFirst(), defaults, lanes, config);
        var high = EffectiveLaneSettings.resolve(definitions.getLast(), defaults, lanes, config);
        assertEquals("global-model", low.model());
        assertEquals(0.2, low.temperature());
        assertEquals(50000, low.toolPass().memoryTokens());
        assertEquals(50, low.toolPass().maxToolCalls());
        assertEquals(PartitionKeyStrategy.AGENT_RUN, definitions.getFirst().partitionKeyStrategy());
        assertEquals("high-model", high.model());
        assertEquals(0.7, high.temperature());
        assertEquals("test-lane-credential", high.apiKey());
        assertEquals(7000, high.toolPass().memoryTokens());
        assertEquals(PartitionKeyStrategy.JOB, definitions.getLast().partitionKeyStrategy());
    }
}
