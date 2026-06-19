package us.nusun.llmshare.tests.runtime;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.runtime.AgentRuntimeDefinition;
import us.nusun.llmshare.runtime.AgentRuntimeRegistry;
import us.nusun.llmshare.team.AgentPersonaRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AgentRuntimeRegistryTest {
    @Inject
    AgentRuntimeRegistry registry;

    @Inject
    AgentPersonaRegistry agentPersonaRegistry;

    @Test
    void knownPersonasResolveToRuntimeDefinitions() {
        AgentRuntimeDefinition director = registry.getRequired("software-team/director");
        AgentRuntimeDefinition projectManager = registry.getRequired("software-team/project-manager");
        AgentRuntimeDefinition productOwner = registry.getRequired("software-team/product-owner");
        AgentRuntimeDefinition developer = registry.getRequired("software-team/application-developer");
        AgentRuntimeDefinition testObserver = registry.getRequired("software-team/test-observer");

        assertEquals("directorAgentConfig", director.agentConfigurationBeanName());
        assertEquals("projectManagerAgentConfig", projectManager.agentConfigurationBeanName());
        assertEquals("productOwnerAgentConfig", productOwner.agentConfigurationBeanName());
        assertEquals("applicationDeveloperAgentConfig", developer.agentConfigurationBeanName());
        assertEquals("testObserverAgentConfig", testObserver.agentConfigurationBeanName());
        assertNotEquals(director.identityKey(), projectManager.identityKey());
        assertNotEquals(projectManager.identityKey(), productOwner.identityKey());
        assertNotEquals(productOwner.identityKey(), developer.identityKey());
    }

    @Test
    void registryDiscoveryIncludesCorePersonasAndTestProvider() {
        assertTrue(agentPersonaRegistry.allPersonas().stream().anyMatch(persona -> persona.roleId().equals("software-team/director")));
        assertTrue(agentPersonaRegistry.allPersonas().stream().anyMatch(persona -> persona.roleId().equals("software-team/project-manager")));
        assertTrue(agentPersonaRegistry.allPersonas().stream().anyMatch(persona -> persona.roleId().equals("software-team/product-owner")));
        assertTrue(agentPersonaRegistry.allPersonas().stream().anyMatch(persona -> persona.roleId().equals("software-team/application-developer")));
        assertTrue(agentPersonaRegistry.teamPersonas().stream().anyMatch(persona -> persona.roleId().equals("software-team/test-observer")));
    }

    @Test
    void unknownRoleFailsClearly() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> registry.getRequired("software-team/unknown"));

        assertEquals("Unknown agent runtime role: software-team/unknown", failure.getMessage());
    }
}
