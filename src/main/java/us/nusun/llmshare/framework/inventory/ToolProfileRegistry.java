package us.nusun.llmshare.framework.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import us.nusun.llmshare.framework.model.ToolProfile;

import java.util.List;
import java.util.Map;

@ApplicationScoped
@Named("toolProfileCatalog")
public class ToolProfileRegistry {
    public static final String NONE_ID = "none";
    public static final ToolProfile NONE = ToolProfile.builder(NONE_ID).build();

    private final Map<String, ToolProfile> profiles;
    private final CatalogJson json;

    @Inject
    public ToolProfileRegistry(Instance<ToolProfileProvider> providers, ObjectMapper objectMapper) {
        this(providers.stream().flatMap(provider -> provider.toolProfiles().stream()).toList(), objectMapper);
    }

    public ToolProfileRegistry(List<ToolProfile> profiles) {
        this(profiles, new ObjectMapper());
    }

    public ToolProfileRegistry(List<ToolProfile> profiles, ObjectMapper objectMapper) {
        for (ToolProfile profile : profiles == null ? List.<ToolProfile>of() : profiles) {
            if (NONE_ID.equals(profile.id())) {
                throw new IllegalArgumentException("Tool profile id 'none' is reserved by the framework");
            }
        }
        this.profiles = CatalogIndex.uniqueById(profiles, ToolProfile::id, "Duplicate tool profile id: ");
        this.json = new CatalogJson(objectMapper);
    }

    public ToolProfile getRequired(String id) {
        String key = id == null || id.isBlank() ? NONE_ID : id;
        if (NONE_ID.equals(key)) {
            return NONE;
        }
        ToolProfile profile = profiles.get(key);
        if (profile == null) {
            throw new IllegalArgumentException("Unknown tool profile: " + key);
        }
        return profile;
    }

    public List<ToolProfile> all() {
        return profiles.values().stream().sorted(java.util.Comparator.comparing(ToolProfile::id)).toList();
    }

    public String allJson() {
        return json.write(all(), "tool profile");
    }

    public String promptJson() {
        return json.write(all().stream().map(ToolProfilePromptView::from).toList(), "tool profile prompt");
    }

    public record ToolProfilePromptView(String id, String promptGuidance, java.util.List<String> capabilityIds) {
        private static ToolProfilePromptView from(ToolProfile profile) {
            return new ToolProfilePromptView(
                    profile.id(),
                    profile.promptGuidance(),
                    profile.servers().stream().sorted().toList());
        }
    }
}
