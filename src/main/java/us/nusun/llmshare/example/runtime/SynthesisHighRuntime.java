package us.nusun.llmshare.example.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import us.nusun.llmshare.framework.model.ExecutionRuntimeDefinition;
import us.nusun.llmshare.framework.inventory.ExecutionRuntimeProvider;

@ApplicationScoped
public class SynthesisHighRuntime implements ExecutionRuntimeProvider {
    public static final String ID = "synthesis-high";
    @Override
    public ExecutionRuntimeDefinition executionRuntime() {
        return ExecutionRuntimeDefinition.builder(SynthesisHighRuntime.ID)
                .lane(HighPassLane.ID)
                .toolCalling(false)
                .agentName("synthesis-high")
                .agentFactoryBeanName("executionRuntimeAgentFactory")
                .build();
    }
}
