package us.nusun.llmshare.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import us.nusun.llmshare.framework.model.ToolProfile;
import us.nusun.llmshare.framework.inventory.ToolProfileProvider;

import java.util.List;

@ApplicationScoped
public class SourceControlToolProfile implements ToolProfileProvider {
    public static final String ID = "source-control";

    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder(ID)
                .server("gitea")
                .denyTools("gitea", "delete_repository", "delete_branch")
                .maxResultCharacters(6000)
                .build());
    }
}
