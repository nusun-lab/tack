package us.nusun.tack.framework.inference.lane;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithParentName;

import java.util.Map;
import java.util.Optional;

@ConfigMapping(prefix = "llm.inference-lane")
public interface LaneConfiguration {
    @WithParentName
    Map<String, Lane> lanes();

    interface Lane {
        Optional<String> requestTopic();
        Optional<String> replyTopic();
        Optional<String> endpoint();
        Optional<String> model();
        Optional<String> apiKey();
        @WithDefault("1")
        int parallelism();
        Optional<PartitionKeyStrategy> partitionKeyStrategy();
        Optional<Double> temperature();
        Optional<Double> topP();
        Optional<Double> presencePenalty();
        Optional<Integer> topK();
        Optional<Double> minP();
        Optional<Double> repetitionPenalty();
        Optional<Integer> contextWindow();
        Optional<Integer> maxTokens();
        Optional<String> reasoningEffort();
        Optional<Integer> maxToolCallingRoundTrips();
        ToolMemory toolMemory();
    }

    interface ToolMemory {
        Optional<Integer> maxTokens();
        Optional<Double> compactionThreshold();
        Optional<Integer> recentToolInteractions();
    }
}
