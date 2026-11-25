package us.nusun.tack.framework.tool;

import us.nusun.tack.framework.CatalogIndex;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ToolProfileRegistry {
    public static final String NONE_ID = "none";
    public static final ToolProfile NONE = ToolProfile.builder(NONE_ID).build();

    private final Map<String, ToolProfile> profiles;

    @Inject
    public ToolProfileRegistry(Instance<ToolProfileProvider> providers) {
        this(providers.stream().flatMap(provider -> provider.toolProfiles().stream()).toList());
    }

    public ToolProfileRegistry(List<ToolProfile> profiles) {
        for (ToolProfile profile : profiles == null ? List.<ToolProfile>of() : profiles) {
            if (NONE_ID.equals(profile.id())) {
                throw new IllegalArgumentException("Tool profile id 'none' is reserved by the framework");
            }
        }
        this.profiles = CatalogIndex.uniqueById(profiles, ToolProfile::id, "Duplicate tool profile id: ");
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

    public List<ToolProfileView> views() {
        return all().stream().map(ToolProfileView::of).toList();
    }
}
