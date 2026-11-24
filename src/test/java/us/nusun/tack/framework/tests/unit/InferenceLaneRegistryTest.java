package us.nusun.tack.framework.tests.unit;

import org.junit.jupiter.api.Test;
import us.nusun.tack.framework.inventory.InferenceLaneRegistry;
import us.nusun.tack.framework.model.InferenceLaneDefinition;
import us.nusun.tack.framework.model.PartitionKeyStrategy;
import us.nusun.tack.framework.tests.support.FrameworkFixtures;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InferenceLaneRegistryTest {
    @Test
    void lanesDefaultToSingleWorkerAgentRunPartitioning() {
        InferenceLaneRegistry registry = FrameworkFixtures.inferenceLaneRegistry();

        assertEquals(1, registry.getRequired("low-pass").parallelism());
        assertEquals(1, registry.getRequired("high-pass").parallelism());
        assertEquals(PartitionKeyStrategy.AGENT_RUN, registry.getRequired("low-pass").partitionKeyStrategy());
        assertEquals(PartitionKeyStrategy.AGENT_RUN, registry.getRequired("high-pass").partitionKeyStrategy());
        assertEquals("llm.requests.low", registry.getRequired("low-pass").requestTopic());
        assertEquals("llm.requests.high", registry.getRequired("high-pass").requestTopic());
        assertEquals("http://low-vllm.example/v1", registry.getRequired("low-pass").endpoint());
        assertEquals("http://high-vllm.example/v1", registry.getRequired("high-pass").endpoint());
        assertEquals("LLM_LOW_API_KEY", registry.getRequired("low-pass").apiKeyEnvVariable());
        assertEquals("LLM_HIGH_API_KEY", registry.getRequired("high-pass").apiKeyEnvVariable());
    }

    @Test
    void laneIterationPreservesRegistrationOrder() {
        InferenceLaneRegistry registry = new InferenceLaneRegistry(List.of(
                InferenceLaneDefinition.builder("z-lane")
                        .requestTopic("llm.requests.z")
                        .responseTopic("llm.replies.z")
                        .build(),
                InferenceLaneDefinition.builder("a-lane")
                        .requestTopic("llm.requests.a")
                        .responseTopic("llm.replies.a")
                        .build()));

        assertEquals(List.of("z-lane", "a-lane"), registry.allLanes().stream().map(InferenceLaneDefinition::id).toList());
    }

    @Test
    void duplicateLaneIdsFailClearly() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> new InferenceLaneRegistry(List.of(
                InferenceLaneDefinition.builder("lane-a")
                        .requestTopic("llm.requests.a")
                        .responseTopic("llm.replies.a")
                        .build(),
                InferenceLaneDefinition.builder("lane-a")
                        .requestTopic("llm.requests.b")
                        .responseTopic("llm.replies.b")
                        .build())));

        assertEquals("Duplicate inference lane id: lane-a", failure.getMessage());
    }

    @Test
    void duplicateRequestTopicsAcrossInferenceLanesFailClearly() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> new InferenceLaneRegistry(List.of(
                InferenceLaneDefinition.builder("lane-a")
                        .requestTopic("llm.requests.shared")
                        .responseTopic("llm.replies.a")
                        .build(),
                InferenceLaneDefinition.builder("lane-b")
                        .requestTopic("llm.requests.shared")
                        .responseTopic("llm.replies.b")
                        .build())));

        assertTrue(failure.getMessage().contains("Duplicate inference lane request topic 'llm.requests.shared'"));
        assertTrue(failure.getMessage().contains("'lane-a'"));
        assertTrue(failure.getMessage().contains("'lane-b'"));
    }

    @Test
    void duplicateReplyTopicsRemainAllowedAcrossInferenceLanes() {
        InferenceLaneRegistry registry = new InferenceLaneRegistry(List.of(
                InferenceLaneDefinition.builder("lane-a")
                        .requestTopic("llm.requests.a")
                        .responseTopic("llm.replies.shared")
                        .build(),
                InferenceLaneDefinition.builder("lane-b")
                        .requestTopic("llm.requests.b")
                        .responseTopic("llm.replies.shared")
                        .build()));

        assertEquals(List.of("lane-a", "lane-b"), registry.allLanes().stream().map(InferenceLaneDefinition::id).toList());
    }

    @Test
    void unknownLaneIdsFailClearly() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> FrameworkFixtures.inferenceLaneRegistry().getRequired("missing"));

        assertEquals("Unknown inference lane: missing", failure.getMessage());
    }
}
