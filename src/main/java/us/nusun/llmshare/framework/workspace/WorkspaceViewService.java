package us.nusun.llmshare.framework.workspace;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import us.nusun.llmshare.framework.inventory.PersonaCatalog;
import us.nusun.llmshare.framework.inventory.RoutineCatalog;
import us.nusun.llmshare.framework.inventory.ToolProfileRegistry;
import us.nusun.llmshare.framework.projection.ProjectionStore;
import us.nusun.llmshare.framework.inventory.InferenceLaneRegistry;

import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
@Named("workspaceViewService")
public class WorkspaceViewService {
    @Inject PersonaCatalog personas;
    @Inject RoutineCatalog routines;
    @Inject InferenceLaneRegistry lanes;
    @Inject ToolProfileRegistry toolProfiles;
    @Inject ProjectionStore projections;

    public Map<String, Object> view() {
        LinkedHashMap<String, Object> view = new LinkedHashMap<>();
        view.put("personas", personas.all());
        view.put("routines", routines.all());
        view.put("inferenceLanes", lanes.allLanes());
        view.put("toolProfiles", toolProfiles.all());
        view.put("agentRunProjections", projections.all());
        return view;
    }
}
