package us.nusun.llmshare.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import us.nusun.llmshare.team.AgentPersonaDefinition;
import us.nusun.llmshare.team.AgentPersonaRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class AgentRuntimeRegistry {
    private final Map<String, AgentRuntimeDefinition> definitions;

    @Inject
    public AgentRuntimeRegistry(AgentPersonaRegistry agentPersonaRegistry) {
        LinkedHashMap<String, AgentRuntimeDefinition> rows = new LinkedHashMap<>();
        for (AgentPersonaDefinition persona : agentPersonaRegistry.allPersonas()) {
            rows.put(persona.roleId(), new AgentRuntimeDefinition(
                    persona.roleId(),
                    persona.routeId() + "-llm",
                    agentConfigurationBeanName(persona.roleSlug()),
                    persona.roleSlug(),
                    persona.roleSlug()));
        }
        this.definitions = Map.copyOf(rows);
    }

    public AgentRuntimeDefinition getRequired(String roleId) {
        AgentRuntimeDefinition definition = definitions.get(roleId);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown agent runtime role: " + roleId);
        }
        return definition;
    }

    public List<AgentRuntimeDefinition> allDefinitions() {
        return definitions.values().stream().toList();
    }

    private static String agentConfigurationBeanName(String roleSlug) {
        if (roleSlug == null || roleSlug.isBlank()) {
            throw new IllegalArgumentException("roleSlug must not be blank");
        }
        String[] tokens = roleSlug.split("-");
        StringBuilder name = new StringBuilder(tokens[0]);
        for (int i = 1; i < tokens.length; i++) {
            if (tokens[i].isBlank()) {
                continue;
            }
            name.append(Character.toUpperCase(tokens[i].charAt(0)));
            name.append(tokens[i].substring(1));
        }
        return name + "AgentConfig";
    }
}
