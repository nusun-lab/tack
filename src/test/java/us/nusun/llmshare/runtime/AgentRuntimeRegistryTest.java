package us.nusun.llmshare.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentRuntimeRegistryTest {
    private final AgentRuntimeRegistry registry = new AgentRuntimeRegistry();

    @Test
    void knownRolesResolveToDistinctRuntimeDefinitions() {
        AgentRuntimeDefinition director = registry.getRequired("software-team/director");
        AgentRuntimeDefinition projectManager = registry.getRequired("software-team/project-manager");
        AgentRuntimeDefinition productOwner = registry.getRequired("software-team/product-owner");
        AgentRuntimeDefinition developer = registry.getRequired("software-team/application-developer");

        assertEquals("directorAgentConfig", director.agentConfigurationBeanName());
        assertEquals("projectManagerAgentConfig", projectManager.agentConfigurationBeanName());
        assertEquals("productOwnerAgentConfig", productOwner.agentConfigurationBeanName());
        assertEquals("applicationDeveloperAgentConfig", developer.agentConfigurationBeanName());
        assertNotEquals(director.identityKey(), projectManager.identityKey());
        assertNotEquals(projectManager.identityKey(), productOwner.identityKey());
        assertNotEquals(productOwner.identityKey(), developer.identityKey());
    }

    @Test
    void unknownRoleFailsClearly() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> registry.getRequired("software-team/unknown"));

        assertEquals("Unknown agent runtime role: software-team/unknown", failure.getMessage());
    }
}
