package us.nusun.llmshare.framework.tests.unit;

import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.model.ExecutionRuntimeDefinition;
import us.nusun.llmshare.framework.inventory.ExecutionRuntimeRegistry;
import us.nusun.llmshare.framework.model.InferenceLaneDefinition;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;

import java.util.Map;
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
    void lanesAreSerializedIndependently() {
        ExecutionRuntimeRegistry registry = FrameworkFixtures.executionRuntimeRegistry();

        assertEquals(1, registry.laneRequired("low-pass").concurrency());
        assertEquals(1, registry.laneRequired("high-pass").concurrency());
        assertEquals("llm.requests.low", registry.laneRequired("low-pass").requestTopic());
        assertEquals("llm.requests.high", registry.laneRequired("high-pass").requestTopic());
        assertEquals("http://low-vllm.example/v1", registry.laneRequired("low-pass").endpoint());
        assertEquals("http://high-vllm.example/v1", registry.laneRequired("high-pass").endpoint());
        assertEquals("LLM_LOW_API_KEY", registry.laneRequired("low-pass").apiKeyEnvVariable());
        assertEquals("LLM_HIGH_API_KEY", registry.laneRequired("high-pass").apiKeyEnvVariable());
    }


    @Test
    void registryAcceptsAdditionalUserDefinedRuntimeAndInferenceLaneDefinitions() {
        ExecutionRuntimeRegistry registry = new ExecutionRuntimeRegistry(
                java.util.List.of(
                        InferenceLaneDefinition.builder("research-pass")
                                .requestTopic("llm.requests.research")
                                .responseTopic("llm.replies.research")
                                .concurrency(1)
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
