package us.nusun.llmshare.runtime;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class AgentRuntimeRegistry {
    private final Map<String, AgentRuntimeDefinition> definitions = Map.of(
            "software-team/director", new AgentRuntimeDefinition(
                    "software-team/director",
                    "software-team-director-llm",
                    "directorAgentConfig",
                    "director",
                    "leadership"),
            "software-team/project-manager", new AgentRuntimeDefinition(
                    "software-team/project-manager",
                    "software-team-project-manager-llm",
                    "projectManagerAgentConfig",
                    "project-manager",
                    "planning"),
            "software-team/product-owner", new AgentRuntimeDefinition(
                    "software-team/product-owner",
                    "software-team-product-owner-llm",
                    "productOwnerAgentConfig",
                    "product-owner",
                    "product"),
            "software-team/application-developer", new AgentRuntimeDefinition(
                    "software-team/application-developer",
                    "software-team-application-developer-llm",
                    "applicationDeveloperAgentConfig",
                    "application-developer",
                    "development"),
            "software-team/routine-formatter", new AgentRuntimeDefinition(
                    "software-team/routine-formatter",
                    "software-team-routine-formatter-llm",
                    "routineFormatterAgentConfig",
                    "routine-formatter",
                    "formatter"));

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
}
