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
    void exposesBuilderDefaultsAndKeepsTheSuppliedLaneConfiguration() {
        var lane = InferenceLaneDefinition.builder("synthetic")
                .requestTopic("test.requests.synthetic")
                .responseTopic("test.replies.synthetic")
                .endpoint("http://synthetic-model.example/v1")
                .apiKeyEnvVariable("TEST_SYNTHETIC_API_KEY")
                .build();
        var registered = new InferenceLaneRegistry(List.of(lane)).getRequired("synthetic");

        assertEquals(1, registered.parallelism());
        assertEquals(PartitionKeyStrategy.AGENT_RUN, registered.partitionKeyStrategy());
        assertEquals("test.requests.synthetic", registered.requestTopic());
        assertEquals("test.replies.synthetic", registered.responseTopic());
        assertEquals("http://synthetic-model.example/v1", registered.endpoint());
        assertEquals("TEST_SYNTHETIC_API_KEY", registered.apiKeyEnvVariable());
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
