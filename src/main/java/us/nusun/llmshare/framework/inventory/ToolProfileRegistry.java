package us.nusun.llmshare.framework.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import us.nusun.llmshare.framework.model.ToolProfile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@Named("toolProfileCatalog")
public class ToolProfileRegistry {
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
        LinkedHashMap<String, ToolProfile> rows = new LinkedHashMap<>();
        for (ToolProfile profile : profiles) {
            if (rows.put(profile.id(), profile) != null) {
                throw new IllegalArgumentException("Duplicate tool profile id: " + profile.id());
            }
        }
        this.profiles = Map.copyOf(rows);
        this.json = new CatalogJson(objectMapper);
    }

    public ToolProfile getRequired(String id) {
        String key = id == null || id.isBlank() ? "none" : id;
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
}
