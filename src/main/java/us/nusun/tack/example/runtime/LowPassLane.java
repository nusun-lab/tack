package us.nusun.tack.example.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import us.nusun.tack.framework.config.LaneConfiguration;
import us.nusun.tack.framework.inventory.InferenceLaneProvider;
import us.nusun.tack.framework.model.InferenceLaneDefinition;
import us.nusun.tack.framework.model.PartitionKeyStrategy;

@ApplicationScoped
public class LowPassLane implements InferenceLaneProvider {
    public static final String ID = "low-pass";
    private static final String CONFIG_NAME = "low";

    private final LaneConfiguration.Lane config;

    public LowPassLane(LaneConfiguration configuration) {
        this.config = configuration.lanes().get(CONFIG_NAME);
    }

    @Override
    public InferenceLaneDefinition inferenceLane() {
        return InferenceLaneDefinition.builder(ID)
                .configName(CONFIG_NAME)
                .requestTopic(config.requestTopic().orElse("llm.requests.low"))
                .responseTopic(config.replyTopic().orElse("llm.replies.low"))
                .parallelism(config.parallelism())
                .partitionKeyStrategy(config.partitionKeyStrategy().orElse(PartitionKeyStrategy.AGENT_RUN))
                .endpoint(config.endpoint().orElse(""))
                .model(config.model().orElse(""))
                .apiKeyConfigKey("llm.inference-lane.low.api-key")
                .apiKeyEnvVariable("LLM_LOW_API_KEY")
                .build();
    }
}
