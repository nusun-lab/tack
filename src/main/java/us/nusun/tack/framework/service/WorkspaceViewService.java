package us.nusun.tack.framework.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import us.nusun.tack.framework.inventory.InferenceLaneRegistry;
import us.nusun.tack.framework.inventory.PersonaCatalog;
import us.nusun.tack.framework.inventory.RoutineCatalog;
import us.nusun.tack.framework.inventory.ToolProfileRegistry;
import us.nusun.tack.framework.model.AgentRunProjection;
import us.nusun.tack.framework.model.InferenceLaneDefinition;
import us.nusun.tack.framework.model.PersonaView;
import us.nusun.tack.framework.model.RoutineDefinition;
import us.nusun.tack.framework.model.ToolProfileView;
import us.nusun.tack.framework.projection.ProjectionStore;

import java.util.List;

@ApplicationScoped
@Named("workspaceViewService")
public class WorkspaceViewService {
    private final List<PersonaView> personas;
    private final List<RoutineDefinition> routines;
    private final List<InferenceLaneDefinition> lanes;
    private final List<ToolProfileView> toolProfiles;
    private final ProjectionStore projections;

    public WorkspaceViewService(PersonaCatalog personas, RoutineCatalog routines, InferenceLaneRegistry lanes,
                                ToolProfileRegistry toolProfiles, ProjectionStore projections) {
        this.personas = personas.views();
        this.routines = routines.all();
        this.lanes = lanes.allLanes();
        this.toolProfiles = toolProfiles.views();
        this.projections = projections;
    }

    public WorkspaceView view() {
        return new WorkspaceView(personas, routines, lanes, toolProfiles, projections.all());
    }

    public record WorkspaceView(
            List<PersonaView> personas,
            List<RoutineDefinition> routines,
            List<InferenceLaneDefinition> inferenceLanes,
            List<ToolProfileView> toolProfiles,
            List<AgentRunProjection> agentRunProjections
    ) {}
}
