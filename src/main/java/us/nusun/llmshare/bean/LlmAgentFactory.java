package us.nusun.llmshare.bean;

import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.apache.camel.component.langchain4j.agent.api.AgentConfiguration;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.nusun.llmshare.mcp.McpClientFactory;
import us.nusun.llmshare.memory.ApproximateTokenCountEstimator;
import us.nusun.llmshare.memory.EphemeralToolPassChatMemoryProvider;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Singleton
public class LlmAgentFactory {
    private static final Logger LOG = LoggerFactory.getLogger(LlmAgentFactory.class);

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
    @Identifier("routineFormatterChatModel")
    OpenAiChatModel routineFormatterChatModel(
            @ConfigProperty(name = "llm.openai.base-url") String baseUrl,
            @ConfigProperty(name = "llm.openai.api-key") String apiKey,
            @ConfigProperty(name = "llm.openai.model") String model,
            @ConfigProperty(name = "llm.openai.formatter-temperature", defaultValue = "0") double temperature,
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
    TokenCountEstimator toolPassTokenCountEstimator(
            @ConfigProperty(name = "llm.openai.model") String model) {
        try {
            return new OpenAiTokenCountEstimator(model);
        } catch (IllegalArgumentException e) {
            LOG.warn("Falling back to approximate token estimation for unknown OpenAI-compatible model '{}'", model);
            return new ApproximateTokenCountEstimator();
        }
    }

    @Produces
    @Singleton
    @Named("directorAgentConfig")
    AgentConfiguration directorAgentConfig(
            OpenAiChatModel chatModel,
            McpClientFactory mcpClientFactory,
            TokenCountEstimator toolPassTokenCountEstimator,
            @ConfigProperty(name = "llm.agent.memory.max-tokens", defaultValue = "24576") int toolPassMemoryMaxTokens) {
        return roleAgentConfig(chatModel, mcpClientFactory, toolPassTokenCountEstimator, toolPassMemoryMaxTokens,
                "director", "leadership");
    }

    @Produces
    @Singleton
    @Named("projectManagerAgentConfig")
    AgentConfiguration projectManagerAgentConfig(
            OpenAiChatModel chatModel,
            McpClientFactory mcpClientFactory,
            TokenCountEstimator toolPassTokenCountEstimator,
            @ConfigProperty(name = "llm.agent.memory.max-tokens", defaultValue = "24576") int toolPassMemoryMaxTokens) {
        return roleAgentConfig(chatModel, mcpClientFactory, toolPassTokenCountEstimator, toolPassMemoryMaxTokens,
                "project-manager", "planning");
    }

    @Produces
    @Singleton
    @Named("productOwnerAgentConfig")
    AgentConfiguration productOwnerAgentConfig(
            OpenAiChatModel chatModel,
            McpClientFactory mcpClientFactory,
            TokenCountEstimator toolPassTokenCountEstimator,
            @ConfigProperty(name = "llm.agent.memory.max-tokens", defaultValue = "24576") int toolPassMemoryMaxTokens) {
        return roleAgentConfig(chatModel, mcpClientFactory, toolPassTokenCountEstimator, toolPassMemoryMaxTokens,
                "product-owner", "product");
    }

    @Produces
    @Singleton
    @Named("applicationDeveloperAgentConfig")
    AgentConfiguration applicationDeveloperAgentConfig(
            OpenAiChatModel chatModel,
            McpClientFactory mcpClientFactory,
            TokenCountEstimator toolPassTokenCountEstimator,
            @ConfigProperty(name = "llm.agent.memory.max-tokens", defaultValue = "24576") int toolPassMemoryMaxTokens) {
        return roleAgentConfig(chatModel, mcpClientFactory, toolPassTokenCountEstimator, toolPassMemoryMaxTokens,
                "application-developer", "development");
    }

    @Produces
    @Singleton
    @Named("routineFormatterAgentConfig")
    AgentConfiguration routineFormatterAgentConfig(@Identifier("routineFormatterChatModel") OpenAiChatModel chatModel) {
        return new AgentConfiguration().withChatModel(chatModel);
    }

    private AgentConfiguration roleAgentConfig(
            OpenAiChatModel chatModel,
            McpClientFactory mcpClientFactory,
            TokenCountEstimator toolPassTokenCountEstimator,
            int toolPassMemoryMaxTokens,
            String identityKey,
            String toolProfile) {
        List<McpClient> mcpClients = mcpClientFactory.clientsFor(identityKey, toolProfile);
        AgentConfiguration configuration = new AgentConfiguration()
                .withChatModel(chatModel)
                .withChatMemoryProvider(new EphemeralToolPassChatMemoryProvider(
                        toolPassMemoryMaxTokens,
                        toolPassTokenCountEstimator,
                        true));
        if (!mcpClients.isEmpty()) {
            configuration.withMcpClients(mcpClients)
                    .withMcpToolProviderFilter(mcpClientFactory.toolFilterFor(toolProfile));
        }
        return configuration;
    }
}
