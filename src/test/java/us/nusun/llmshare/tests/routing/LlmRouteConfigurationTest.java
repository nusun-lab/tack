package us.nusun.llmshare.tests.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.camel.CamelContext;
import org.apache.camel.ServiceStatus;
import org.apache.camel.component.langchain4j.agent.api.AgentConfiguration;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.model.LlmJobEnvelope;
import us.nusun.llmshare.model.LlmJobRequest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@QuarkusTest
class LlmRouteConfigurationTest {
    @Inject
    CamelContext camelContext;

    @Inject
    OpenAiChatModel openAiChatModel;

    @Inject
    @Named("directorAgentConfig")
    AgentConfiguration directorAgentConfig;

    @Inject
    @Named("projectManagerAgentConfig")
    AgentConfiguration projectManagerAgentConfig;

    @Inject
    @Named("productOwnerAgentConfig")
    AgentConfiguration productOwnerAgentConfig;

    @Inject
    @Named("applicationDeveloperAgentConfig")
    AgentConfiguration applicationDeveloperAgentConfig;

    @Inject
    @Named("routineFormatterAgentConfig")
    AgentConfiguration routineFormatterAgentConfig;

    @Inject
    ObjectMapper objectMapper;

    @Test
    void allRoleConfigurationsReuseTheSameSharedModel() {
        assertSame(openAiChatModel, directorAgentConfig.getChatModel());
        assertSame(openAiChatModel, projectManagerAgentConfig.getChatModel());
        assertSame(openAiChatModel, productOwnerAgentConfig.getChatModel());
        assertSame(openAiChatModel, applicationDeveloperAgentConfig.getChatModel());
    }

    @Test
    void formatterConfigurationUsesADedicatedModel() {
        assertNotNull(routineFormatterAgentConfig.getChatModel());
        assertFalse(routineFormatterAgentConfig.getChatModel() == openAiChatModel);
    }

    @Test
    void workerRouteRemainsSerialized() {
        assertEquals(1, ConfigProvider.getConfig().getValue("llm.worker.consumers-count", Integer.class));
        assertEquals(1, ConfigProvider.getConfig().getValue("llm.worker.max-poll-records", Integer.class));
        assertEquals(ServiceStatus.Started, camelContext.getRouteController().getRouteStatus("call-llm-agent"));
    }

    @Test
    void llmJobEnvelopeDoesNotContainRuntimeCredentials() throws Exception {
        LlmJobEnvelope envelope = LlmJobEnvelope.queued(LlmJobRequest.fromPrompt(
                UUID.randomUUID(),
                "software-team/director",
                "director",
                "Inspect the backlog.",
                "System prompt",
                null));

        String serialized = objectMapper.writeValueAsString(envelope);
        assertFalse(serialized.contains("DIRECTOR_GITEA_TOKEN"));
        assertFalse(serialized.contains("DIRECTOR_MATTERMOST_TOKEN"));
        assertFalse(serialized.contains("DIRECTOR_PLANE_TOKEN"));
        assertFalse(serialized.toLowerCase().contains("x-api-key"));
        assertFalse(serialized.toLowerCase().contains("authorization"));
    }
}
