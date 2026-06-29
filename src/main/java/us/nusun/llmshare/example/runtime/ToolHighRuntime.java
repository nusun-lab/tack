package us.nusun.llmshare.example.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import us.nusun.llmshare.framework.model.ExecutionRuntimeDefinition;
import us.nusun.llmshare.framework.inventory.ExecutionRuntimeProvider;

@ApplicationScoped
public class ToolHighRuntime implements ExecutionRuntimeProvider {
    public static final String ID = "tool-high";
    @Override
    public ExecutionRuntimeDefinition executionRuntime() {
        return ExecutionRuntimeDefinition.builder(ToolHighRuntime.ID)
                .lane(HighPassLane.ID)
                .toolCalling(true)
                .agentName("tool-high")
                .agentFactoryBeanName("executionRuntimeAgentFactory")
                .build();
    }
}
