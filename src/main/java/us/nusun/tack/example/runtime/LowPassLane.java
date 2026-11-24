package us.nusun.tack.example.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.tack.framework.model.InferenceLaneDefinition;
import us.nusun.tack.framework.inventory.InferenceLaneProvider;
import us.nusun.tack.framework.model.PartitionKeyStrategy;

@ApplicationScoped
public class LowPassLane implements InferenceLaneProvider {
    public static final String ID = "low-pass";
    public static final String API_KEY_CONFIG_KEY = "llm.inference-lane.low.api-key";
    public static final String API_KEY_ENV_VARIABLE = "LLM_LOW_API_KEY";

    @ConfigProperty(name = "llm.inference-lane.low.request-topic", defaultValue = "llm.requests.low")
    String requestTopic;

    @ConfigProperty(name = "llm.inference-lane.low.reply-topic", defaultValue = "llm.replies.low")
    String responseTopic;

    @ConfigProperty(name = "llm.inference-lane.low.endpoint", defaultValue = "")
    String endpoint;

    @ConfigProperty(name = "llm.inference-lane.low.model", defaultValue = "")
    String model;

    @ConfigProperty(name = "llm.inference-lane.low.parallelism", defaultValue = "1")
    int parallelism = 1;

    @ConfigProperty(name = "llm.inference-lane.low.partition-key-strategy", defaultValue = "AGENT_RUN")
    PartitionKeyStrategy partitionKeyStrategy = PartitionKeyStrategy.AGENT_RUN;

    @Override
    public InferenceLaneDefinition inferenceLane() {
        return InferenceLaneDefinition.builder(LowPassLane.ID)
                .requestTopic(requestTopic)
                .responseTopic(responseTopic)
                .parallelism(parallelism)
                .partitionKeyStrategy(partitionKeyStrategy)
                .endpoint(endpoint)
                .model(model)
                .apiKeyConfigKey(API_KEY_CONFIG_KEY)
                .apiKeyEnvVariable(API_KEY_ENV_VARIABLE)
                .build();
    }
}
