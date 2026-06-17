package us.nusun.llmshare.bean;

import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.apache.camel.component.langchain4j.agent.api.AgentConfiguration;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.mcp.McpClientFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Singleton
public class LlmAgentFactory {
    @Produces
    @Singleton
    OpenAiChatModel openAiChatModel(
            @ConfigProperty(name = "llm.openai.base-url") String baseUrl,
            @ConfigProperty(name = "llm.openai.api-key") String apiKey,
            @ConfigProperty(name = "llm.openai.model") String model,
            @ConfigProperty(name = "llm.openai.temperature") double temperature,
            @ConfigProperty(name = "llm.openai.max-tokens") int maxTokens,
            @ConfigProperty(name = "llm.openai.timeout-ms") long timeoutMs,
            @ConfigProperty(name = "llm.thinking-off.enabled") boolean thinkingOffEnabled,
            @ConfigProperty(name = "llm.openai.strict-json-schema", defaultValue = "true") boolean strictJsonSchema) {
        var chatModelBuilder = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .strictJsonSchema(strictJsonSchema)
                .timeout(Duration.ofMillis(timeoutMs));

        if (thinkingOffEnabled) {
            chatModelBuilder.customParameters(Map.of(
                    "chat_template_kwargs", Map.of("enable_thinking", false)
            ));
        }

        return chatModelBuilder.build();
    }

    @Produces
    @Singleton
    @Named("directorAgentConfig")
    AgentConfiguration directorAgentConfig(OpenAiChatModel chatModel, McpClientFactory mcpClientFactory) {
        return roleAgentConfig(chatModel, mcpClientFactory, "director", "leadership");
    }

    @Produces
    @Singleton
    @Named("projectManagerAgentConfig")
    AgentConfiguration projectManagerAgentConfig(OpenAiChatModel chatModel, McpClientFactory mcpClientFactory) {
        return roleAgentConfig(chatModel, mcpClientFactory, "project-manager", "planning");
    }

    @Produces
    @Singleton
    @Named("productOwnerAgentConfig")
    AgentConfiguration productOwnerAgentConfig(OpenAiChatModel chatModel, McpClientFactory mcpClientFactory) {
        return roleAgentConfig(chatModel, mcpClientFactory, "product-owner", "product");
    }

    @Produces
    @Singleton
    @Named("applicationDeveloperAgentConfig")
    AgentConfiguration applicationDeveloperAgentConfig(OpenAiChatModel chatModel, McpClientFactory mcpClientFactory) {
        return roleAgentConfig(chatModel, mcpClientFactory, "application-developer", "development");
    }

    private AgentConfiguration roleAgentConfig(
            OpenAiChatModel chatModel,
            McpClientFactory mcpClientFactory,
            String identityKey,
            String toolProfile) {
        List<McpClient> mcpClients = mcpClientFactory.clientsFor(identityKey, toolProfile);
        AgentConfiguration configuration = new AgentConfiguration().withChatModel(chatModel);
        if (!mcpClients.isEmpty()) {
            configuration.withMcpClients(mcpClients)
                    .withMcpToolProviderFilter(mcpClientFactory.toolFilterFor(toolProfile));
        }
        return configuration;
    }
}
