package us.nusun.tack.framework.inventory;

import us.nusun.tack.framework.model.RoutineDefinition;

import java.util.List;

public interface RoutineProvider {
    List<RoutineDefinition> routines();
}
