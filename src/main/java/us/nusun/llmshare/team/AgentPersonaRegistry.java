package us.nusun.llmshare.team;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@ApplicationScoped
public class AgentPersonaRegistry {
    private final TeamOperatingContext teamContext;
    private final Map<String, AgentPersonaDefinition> personasByRoleId;
    private final Map<String, AgentPersonaDefinition> personasByRouteId;
    private final Map<String, AgentPersonaDefinition> personasByRouteUri;

    @Inject
    public AgentPersonaRegistry(Instance<AgentPersonaProvider> providers, SoftwareTeamContext softwareTeamContext) {
        this(providers.stream().map(AgentPersonaProvider::persona).toList(), softwareTeamContext.context());
    }

    public AgentPersonaRegistry(List<AgentPersonaDefinition> personas, TeamOperatingContext teamContext) {
        this.teamContext = Objects.requireNonNull(teamContext, "teamContext");
        List<AgentPersonaDefinition> ordered = personas.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(AgentPersonaDefinition::roleId))
                .toList();
        this.personasByRoleId = indexBy(ordered, AgentPersonaDefinition::roleId);
        this.personasByRouteId = indexBy(ordered, AgentPersonaDefinition::routeId);
        this.personasByRouteUri = indexBy(ordered, AgentPersonaDefinition::routeUri);
    }

    public TeamOperatingContext teamContext() {
        return teamContext;
    }

    public List<AgentPersonaDefinition> allPersonas() {
        return List.copyOf(personasByRoleId.values());
    }

    public List<AgentPersonaDefinition> teamPersonas() {
        return allPersonas().stream()
                .filter(AgentPersonaDefinition::teamMember)
                .toList();
    }

    public List<AgentPersonaDefinition> directorAssignablePersonas() {
        return allPersonas().stream()
                .filter(AgentPersonaDefinition::assignableByDirector)
                .toList();
    }

    public AgentPersonaDefinition getRequired(String roleId) {
        AgentPersonaDefinition persona = personasByRoleId.get(roleId);
        if (persona == null) {
            throw new IllegalArgumentException("Unknown agent persona: " + roleId);
        }
        return persona;
    }

    public AgentPersonaDefinition findByRouteId(String routeId) {
        return personasByRouteId.get(routeId);
    }

    public AgentPersonaDefinition findByRouteUri(String routeUri) {
        return personasByRouteUri.get(routeUri);
    }

    public String directorRoleGuide() {
        return directorAssignablePersonas().stream()
                .map(role -> "- " + role.roleId() + ": " + role.capabilitySummary())
                .collect(Collectors.joining("\n"));
    }

    public String directorTeamRoster() {
        String roleLines = teamPersonas().stream()
                .map(role -> "- " + role.displayName() + " (" + role.roleId() + ", " + role.agentLabel() + ")")
                .collect(Collectors.joining("\n"));
        return """
                - Owner: %s (%s)
                %s
                """.formatted(teamContext.ownerDisplayName(), teamContext.ownerIdentity(), roleLines);
    }

    public List<Map<String, Object>> identityRegistryView() {
        return teamPersonas().stream()
                .map(persona -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("roleId", persona.roleId());
                    row.put("roleSlug", persona.roleSlug());
                    row.put("displayName", persona.displayName());
                    row.put("agentLabel", persona.agentLabel());
                    row.put("capabilitySummary", persona.capabilitySummary());
                    row.put("identities", persona.platformIdentities());
                    return row;
                })
                .toList();
    }

    public void validateAssignments(Map<String, String> assignments) {
        if (assignments == null || assignments.isEmpty()) {
            throw new IllegalArgumentException("Director assignments must include at least one role");
        }
        for (Map.Entry<String, String> entry : assignments.entrySet()) {
            AgentPersonaDefinition persona = getRequired(entry.getKey());
            if (!persona.assignableByDirector()) {
                throw new IllegalArgumentException("Director assignment role is not assignable: " + entry.getKey());
            }
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                throw new IllegalArgumentException("Director assignment for role " + entry.getKey() + " is blank");
            }
        }
    }

    private static Map<String, AgentPersonaDefinition> indexBy(
            List<AgentPersonaDefinition> personas,
            java.util.function.Function<AgentPersonaDefinition, String> keyFn) {
        LinkedHashMap<String, AgentPersonaDefinition> rows = new LinkedHashMap<>();
        for (AgentPersonaDefinition persona : personas) {
            rows.put(keyFn.apply(persona), persona);
        }
        return Map.copyOf(rows);
    }
}
