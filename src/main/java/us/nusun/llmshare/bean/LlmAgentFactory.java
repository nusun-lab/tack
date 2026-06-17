package us.nusun.llmshare.bean;

import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.inject.Named;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.apache.camel.component.langchain4j.agent.api.AgentConfiguration;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.Map;

@Singleton
public class LlmAgentFactory {
    @Produces
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
    @Named("sharedLlmConfig")
    AgentConfiguration sharedLlmConfig(OpenAiChatModel chatModel) {
        return new AgentConfiguration().withChatModel(chatModel);
    }
}
