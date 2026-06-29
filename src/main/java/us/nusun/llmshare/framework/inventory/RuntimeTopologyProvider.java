package us.nusun.llmshare.framework.inventory;

import us.nusun.llmshare.framework.model.ExecutionRuntimeDefinition;
import us.nusun.llmshare.framework.model.InferenceLaneDefinition;

import java.util.List;

public interface RuntimeTopologyProvider {
    List<InferenceLaneDefinition> inferenceLanes();
    List<ExecutionRuntimeDefinition> executionRuntimes();
}
