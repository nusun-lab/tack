package us.nusun.tack.framework.workspace;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import us.nusun.tack.framework.inference.lane.InferenceLaneRegistry;
import us.nusun.tack.framework.persona.PersonaCatalog;
import us.nusun.tack.framework.routine.RoutineCatalog;
import us.nusun.tack.framework.tool.ToolProfileRegistry;
import us.nusun.tack.framework.run.AgentRunProjection;
import us.nusun.tack.framework.inference.lane.InferenceLaneDefinition;
import us.nusun.tack.framework.persona.PersonaView;
import us.nusun.tack.framework.routine.RoutineDefinition;
import us.nusun.tack.framework.tool.ToolProfileView;
import us.nusun.tack.framework.run.ProjectionStore;

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
