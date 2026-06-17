package us.nusun.llmshare.team;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@ApplicationScoped
public class TeamRoleRegistry {
    private final Map<String, TeamRoleDefinition> roles = new LinkedHashMap<>();

    public void register(TeamRoleDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        roles.put(definition.roleId(), definition);
    }

    public TeamRoleDefinition getRequired(String roleId) {
        TeamRoleDefinition definition = roles.get(roleId);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown team role: " + roleId);
        }
        return definition;
    }

    public List<TeamRoleDefinition> allRoles() {
        return List.copyOf(roles.values());
    }

    public String directorRoleGuide() {
        return roles.values().stream()
                .map(role -> "- " + role.roleId() + ": " + role.capabilitySummary())
                .collect(Collectors.joining("\n"));
    }

    public void validateAssignments(Map<String, String> assignments) {
        if (assignments == null || assignments.isEmpty()) {
            throw new IllegalArgumentException("Director assignments must include at least one role");
        }
        for (Map.Entry<String, String> entry : assignments.entrySet()) {
            getRequired(entry.getKey());
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                throw new IllegalArgumentException("Director assignment for role " + entry.getKey() + " is blank");
            }
        }
    }
}
