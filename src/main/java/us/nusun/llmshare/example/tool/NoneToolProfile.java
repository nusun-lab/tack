package us.nusun.llmshare.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import us.nusun.llmshare.framework.model.ToolProfile;
import us.nusun.llmshare.framework.inventory.ToolProfileProvider;

import java.util.List;

@ApplicationScoped
public class NoneToolProfile implements ToolProfileProvider {
    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder("none").maxResultCharacters(0).build());
    }
}
