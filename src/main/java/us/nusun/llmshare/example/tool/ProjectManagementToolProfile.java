package us.nusun.llmshare.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import us.nusun.llmshare.framework.model.ToolProfile;
import us.nusun.llmshare.framework.inventory.ToolProfileProvider;

import java.util.List;

@ApplicationScoped
public class ProjectManagementToolProfile implements ToolProfileProvider {
    public static final String ID = "project-management";

    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder(ID)
                .server("plane")
                .denyTools("plane", "delete_project", "delete_work_item")
                .defaultArgument("plane", "list_projects", "fields", "id,name,identifier")
                .defaultArgument("plane", "list_work_items", "fields", "id,name,sequence_id,state,priority")
                .maxResultCharacters(5000)
                .build());
    }
}
