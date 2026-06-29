package us.nusun.llmshare.example.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import us.nusun.llmshare.framework.model.ExecutionRuntimeDefinition;
import us.nusun.llmshare.framework.inventory.ExecutionRuntimeProvider;

@ApplicationScoped
public class ToolLowRuntime implements ExecutionRuntimeProvider {
    public static final String ID = "tool-low";
    @Override
    public ExecutionRuntimeDefinition executionRuntime() {
        return ExecutionRuntimeDefinition.builder(ToolLowRuntime.ID)
                .lane(LowPassLane.ID)
                .toolCalling(true)
                .agentName("tool-low")
                .agentFactoryBeanName("executionRuntimeAgentFactory")
                .build();
    }
}
