package us.nusun.tack.example.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import us.nusun.tack.framework.config.LaneConfiguration;
import us.nusun.tack.framework.inventory.InferenceLaneProvider;
import us.nusun.tack.framework.model.InferenceLaneDefinition;
import us.nusun.tack.framework.model.PartitionKeyStrategy;

@ApplicationScoped
public class HighPassLane implements InferenceLaneProvider {
    public static final String ID = "high-pass";
    private static final String CONFIG_NAME = "high";

    private final LaneConfiguration.Lane config;

    public HighPassLane(LaneConfiguration configuration) {
        this.config = configuration.lanes().get(CONFIG_NAME);
    }

    @Override
    public InferenceLaneDefinition inferenceLane() {
        return InferenceLaneDefinition.builder(ID)
                .configName(CONFIG_NAME)
                .requestTopic(config.requestTopic().orElse("llm.requests.high"))
                .responseTopic(config.replyTopic().orElse("llm.replies.high"))
                .parallelism(config.parallelism())
                .partitionKeyStrategy(config.partitionKeyStrategy().orElse(PartitionKeyStrategy.JOB))
                .endpoint(config.endpoint().orElse(""))
                .model(config.model().orElse(""))
                .apiKeyConfigKey("llm.inference-lane.high.api-key")
                .apiKeyEnvVariable("LLM_HIGH_API_KEY")
                .build();
    }
}
