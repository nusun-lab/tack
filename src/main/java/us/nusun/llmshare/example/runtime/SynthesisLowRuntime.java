package us.nusun.llmshare.example.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import us.nusun.llmshare.framework.model.ExecutionRuntimeDefinition;
import us.nusun.llmshare.framework.inventory.ExecutionRuntimeProvider;

@ApplicationScoped
public class SynthesisLowRuntime implements ExecutionRuntimeProvider {
    public static final String ID = "synthesis-low";
    @Override
    public ExecutionRuntimeDefinition executionRuntime() {
        return ExecutionRuntimeDefinition.builder(SynthesisLowRuntime.ID)
                .lane(LowPassLane.ID)
                .toolCalling(false)
                .agentName("synthesis-low")
                .agentFactoryBeanName("executionRuntimeAgentFactory")
                .build();
    }
}
