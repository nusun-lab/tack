package us.nusun.llmshare.framework.tests.unit;

import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.model.ExecutionRuntimeDefinition;
import us.nusun.llmshare.framework.inventory.ExecutionRuntimeRegistry;
import us.nusun.llmshare.framework.model.InferenceLaneDefinition;
import us.nusun.llmshare.framework.model.PartitionKeyStrategy;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;

import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeTopologyTest {
    @Test
    void defaultRuntimeTopologyKeepsBehaviorProfilesSeparateFromInferenceLanes() {
        ExecutionRuntimeRegistry registry = FrameworkFixtures.executionRuntimeRegistry();
        Map<String, ExecutionRuntimeDefinition> runtimes = registry.allRuntimes().stream()
                .collect(Collectors.toMap(ExecutionRuntimeDefinition::id, runtime -> runtime));

        assertEquals(4, runtimes.size());
        assertEquals("low-pass", runtimes.get("tool-low").laneId());
        assertEquals("low-pass", runtimes.get("synthesis-low").laneId());
        assertEquals("high-pass", runtimes.get("tool-high").laneId());
        assertEquals("high-pass", runtimes.get("synthesis-high").laneId());
        assertTrue(runtimes.get("tool-high").toolCalling());
        assertFalse(runtimes.get("synthesis-high").toolCalling());
        assertEquals("executionRuntimeAgentFactory", runtimes.get("tool-low").agentFactoryBeanName());
        assertEquals("executionRuntimeAgentFactory", runtimes.get("synthesis-low").agentFactoryBeanName());
        assertEquals("executionRuntimeAgentFactory", runtimes.get("tool-high").agentFactoryBeanName());
        assertEquals("executionRuntimeAgentFactory", runtimes.get("synthesis-high").agentFactoryBeanName());
    }

    @Test
    void lanesDefaultToSingleWorkerAgentRunPartitioning() {
        ExecutionRuntimeRegistry registry = FrameworkFixtures.executionRuntimeRegistry();

        assertEquals(1, registry.laneRequired("low-pass").parallelism());
        assertEquals(1, registry.laneRequired("high-pass").parallelism());
        assertEquals(PartitionKeyStrategy.AGENT_RUN, registry.laneRequired("low-pass").partitionKeyStrategy());
        assertEquals(PartitionKeyStrategy.AGENT_RUN, registry.laneRequired("high-pass").partitionKeyStrategy());
        assertEquals("llm.requests.low", registry.laneRequired("low-pass").requestTopic());
        assertEquals("llm.requests.high", registry.laneRequired("high-pass").requestTopic());
        assertEquals("http://low-vllm.example/v1", registry.laneRequired("low-pass").endpoint());
        assertEquals("http://high-vllm.example/v1", registry.laneRequired("high-pass").endpoint());
        assertEquals("LLM_LOW_API_KEY", registry.laneRequired("low-pass").apiKeyEnvVariable());
        assertEquals("LLM_HIGH_API_KEY", registry.laneRequired("high-pass").apiKeyEnvVariable());
    }

    @Test
    void runtimeAndLaneIterationPreservesRegistrationOrder() {
        ExecutionRuntimeRegistry registry = new ExecutionRuntimeRegistry(
                java.util.List.of(
                        InferenceLaneDefinition.builder("z-lane")
                                .requestTopic("llm.requests.z")
                                .responseTopic("llm.replies.z")
                                .build(),
                        InferenceLaneDefinition.builder("a-lane")
                                .requestTopic("llm.requests.a")
                                .responseTopic("llm.replies.a")
                                .build()),
                java.util.List.of(
                        ExecutionRuntimeDefinition.builder("runtime-z")
                                .lane("z-lane")
                                .agentFactoryBeanName("executionRuntimeAgentFactory")
                                .build(),
                        ExecutionRuntimeDefinition.builder("runtime-a")
                                .lane("a-lane")
                                .agentFactoryBeanName("executionRuntimeAgentFactory")
                                .build()));

        assertEquals(List.of("z-lane", "a-lane"), registry.allLanes().stream().map(InferenceLaneDefinition::id).toList());
        assertEquals(List.of("runtime-z", "runtime-a"), registry.allRuntimes().stream().map(ExecutionRuntimeDefinition::id).toList());
    }

    @Test
    void duplicateRequestTopicsAcrossInferenceLanesFailClearly() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> new ExecutionRuntimeRegistry(
                java.util.List.of(
                        InferenceLaneDefinition.builder("lane-a")
                                .requestTopic("llm.requests.shared")
                                .responseTopic("llm.replies.a")
                                .build(),
                        InferenceLaneDefinition.builder("lane-b")
                                .requestTopic("llm.requests.shared")
                                .responseTopic("llm.replies.b")
                                .build()),
                java.util.List.of()));

        assertTrue(failure.getMessage().contains("Duplicate inference lane request topic 'llm.requests.shared'"));
        assertTrue(failure.getMessage().contains("'lane-a'"));
        assertTrue(failure.getMessage().contains("'lane-b'"));
    }

    @Test
    void duplicateReplyTopicsRemainAllowedAcrossInferenceLanes() {
        ExecutionRuntimeRegistry registry = new ExecutionRuntimeRegistry(
                java.util.List.of(
                        InferenceLaneDefinition.builder("lane-a")
                                .requestTopic("llm.requests.a")
                                .responseTopic("llm.replies.shared")
                                .build(),
                        InferenceLaneDefinition.builder("lane-b")
                                .requestTopic("llm.requests.b")
                                .responseTopic("llm.replies.shared")
                                .build()),
                java.util.List.of());

        assertEquals(List.of("lane-a", "lane-b"), registry.allLanes().stream().map(InferenceLaneDefinition::id).toList());
    }

    @Test
    void registryAcceptsAdditionalUserDefinedRuntimeAndInferenceLaneDefinitions() {
        ExecutionRuntimeRegistry registry = new ExecutionRuntimeRegistry(
                java.util.List.of(
                        InferenceLaneDefinition.builder("research-pass")
                                .requestTopic("llm.requests.research")
                                .responseTopic("llm.replies.research")
                                .parallelism(1)
                                .endpoint("http://research-vllm.example/v1")
                                .apiKeyConfigKey("llm.inference-lane.research.api-key")
                                .apiKeyEnvVariable("LLM_RESEARCH_API_KEY")
                                .build()),
                java.util.List.of(
                        ExecutionRuntimeDefinition.builder("synthesis-research")
                                .lane("research-pass")
                                .toolCalling(false)
                                .agentName("synthesis-research")
                                .agentFactoryBeanName("executionRuntimeAgentFactory")
                                .build()));

        assertEquals("research-pass", registry.getRequired("synthesis-research").laneId());
        assertEquals("http://research-vllm.example/v1", registry.laneRequired("research-pass").endpoint());
        assertEquals("LLM_RESEARCH_API_KEY", registry.laneRequired("research-pass").apiKeyEnvVariable());
    }

    @Test
    void unknownRuntimeFailsClearly() {
        ExecutionRuntimeRegistry registry = FrameworkFixtures.executionRuntimeRegistry();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> registry.getRequired("missing"));
        assertEquals("Unknown execution runtime: missing", failure.getMessage());
    }
}
