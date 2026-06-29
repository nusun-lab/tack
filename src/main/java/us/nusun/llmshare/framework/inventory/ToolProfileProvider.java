package us.nusun.llmshare.framework.inventory;

import us.nusun.llmshare.framework.model.ToolProfile;

import java.util.List;

public interface ToolProfileProvider {
    List<ToolProfile> toolProfiles();
}
