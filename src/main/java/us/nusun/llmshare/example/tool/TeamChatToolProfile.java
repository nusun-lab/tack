package us.nusun.llmshare.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import us.nusun.llmshare.framework.model.ToolProfile;
import us.nusun.llmshare.framework.inventory.ToolProfileProvider;

import java.util.List;

@ApplicationScoped
public class TeamChatToolProfile implements ToolProfileProvider {
    public static final String ID = "team-chat";

    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder(ID)
                .server("mattermost")
                .denyTools("mattermost", "delete_channel", "delete_post")
                .maxResultCharacters(3000)
                .build());
    }
}
