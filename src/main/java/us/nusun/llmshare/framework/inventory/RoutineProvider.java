package us.nusun.llmshare.framework.inventory;

import us.nusun.llmshare.framework.model.RoutineDefinition;

import java.util.List;

public interface RoutineProvider {
    List<RoutineDefinition> routines();
}
