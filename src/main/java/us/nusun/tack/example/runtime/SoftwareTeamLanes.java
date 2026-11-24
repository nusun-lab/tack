package us.nusun.tack.example.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import us.nusun.tack.framework.config.LaneConfiguration;
import us.nusun.tack.framework.inventory.InferenceLaneProvider;
import us.nusun.tack.framework.model.InferenceLaneDefinition;
import us.nusun.tack.framework.model.PartitionKeyStrategy;

import java.util.List;

@ApplicationScoped
public class SoftwareTeamLanes implements InferenceLaneProvider {
    private final LaneConfiguration configuration;

    public SoftwareTeamLanes(LaneConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public List<InferenceLaneDefinition> inferenceLanes() {
        return List.of(
                lane("low", PartitionKeyStrategy.AGENT_RUN, "LLM_LOW_API_KEY"),
                lane("high", PartitionKeyStrategy.JOB, "LLM_HIGH_API_KEY"));
    }

    private InferenceLaneDefinition lane(String name, PartitionKeyStrategy defaultPartition, String apiKeyEnvVariable) {
        var config = configuration.lanes().get(name);
        return InferenceLaneDefinition.builder(name + "-pass")
                .configName(name)
                .requestTopic(config.requestTopic().orElse("llm.requests." + name))
                .responseTopic(config.replyTopic().orElse("llm.replies." + name))
                .parallelism(config.parallelism())
                .partitionKeyStrategy(config.partitionKeyStrategy().orElse(defaultPartition))
                .endpoint(config.endpoint().orElse(""))
                .model(config.model().orElse(""))
                .apiKeyConfigKey("llm.inference-lane." + name + ".api-key")
                .apiKeyEnvVariable(apiKeyEnvVariable)
                .build();
    }
}
