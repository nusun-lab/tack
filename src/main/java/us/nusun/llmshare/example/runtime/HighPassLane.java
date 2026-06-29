package us.nusun.llmshare.example.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.framework.model.InferenceLaneDefinition;
import us.nusun.llmshare.framework.inventory.InferenceLaneProvider;

@ApplicationScoped
public class HighPassLane implements InferenceLaneProvider {
    public static final String ID = "high-pass";
    public static final String API_KEY_CONFIG_KEY = "llm.inference-lane.high.api-key";
    public static final String API_KEY_ENV_VARIABLE = "LLM_HIGH_API_KEY";

    @ConfigProperty(name = "llm.inference-lane.high.request-topic", defaultValue = "llm.requests.high")
    String requestTopic;

    @ConfigProperty(name = "llm.inference-lane.high.reply-topic", defaultValue = "llm.replies.high")
    String responseTopic;

    @ConfigProperty(name = "llm.inference-lane.high.endpoint", defaultValue = "")
    String endpoint;

    @Override
    public InferenceLaneDefinition inferenceLane() {
        return InferenceLaneDefinition.builder(HighPassLane.ID)
                .requestTopic(requestTopic)
                .responseTopic(responseTopic)
                .concurrency(1)
                .endpoint(endpoint)
                .apiKeyConfigKey(API_KEY_CONFIG_KEY)
                .apiKeyEnvVariable(API_KEY_ENV_VARIABLE)
                .build();
    }
}
