package us.nusun.llmshare.framework.llm;

import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.component.langchain4j.agent.api.Agent;
import org.apache.camel.component.langchain4j.agent.api.AgentConfiguration;
import org.apache.camel.component.langchain4j.agent.api.AgentFactory;
import org.apache.camel.component.langchain4j.agent.api.AgentWithMemory;
import org.apache.camel.component.langchain4j.agent.api.AgentWithoutMemory;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.nusun.llmshare.framework.inventory.PersonaCatalog;
import us.nusun.llmshare.framework.mcp.McpClientFactory;
import us.nusun.llmshare.framework.model.ToolProfile;
import us.nusun.llmshare.framework.inventory.ToolProfileRegistry;
import us.nusun.llmshare.framework.memory.ApproximateTokenCountEstimator;
import us.nusun.llmshare.framework.memory.EphemeralToolPassChatMemoryProvider;
import us.nusun.llmshare.framework.model.Persona;
import us.nusun.llmshare.framework.model.ExecutionRuntimeDefinition;
import us.nusun.llmshare.framework.inventory.ExecutionRuntimeRegistry;
import us.nusun.llmshare.framework.model.InferenceLaneDefinition;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Singleton
public class LlmAgentFactory {
    private static final Logger LOG = LoggerFactory.getLogger(LlmAgentFactory.class);

    @Produces
    @Singleton
    TokenCountEstimator toolPassTokenCountEstimator(@ConfigProperty(name = "llm.runtime-default.openai.model") String model) {
        try {
            return new OpenAiTokenCountEstimator(model);
        } catch (IllegalArgumentException e) {
            LOG.warn("Falling back to approximate token estimation for unknown OpenAI-compatible model '{}'", model);
            return new ApproximateTokenCountEstimator();
        }
    }

    @Produces
    @Singleton
    @Named("executionRuntimeAgentFactory")
    AgentFactory executionRuntimeAgentFactory(
            ExecutionRuntimeRegistry runtimes,
            McpClientFactory mcpClientFactory,
            ToolProfileRegistry toolProfiles,
            PersonaCatalog personas,
            TokenCountEstimator tokenCountEstimator,
            @ConfigProperty(name = "llm.runtime-default.openai.base-url") String defaultBaseUrl,
            @ConfigProperty(name = "llm.runtime-default.openai.api-key") String defaultApiKey,
            @ConfigProperty(name = "llm.runtime-default.openai.model") String defaultModel,
            @ConfigProperty(name = "llm.runtime-default.openai.temperature") double temperature,
            @ConfigProperty(name = "llm.runtime-default.openai.max-tokens") int maxTokens,
            @ConfigProperty(name = "llm.runtime-default.openai.timeout-ms") long timeoutMs,
            @ConfigProperty(name = "llm.runtime-default.openai.thinking-off.enabled") boolean thinkingOffEnabled,
            @ConfigProperty(name = "llm.runtime-default.openai.strict-json-schema", defaultValue = "true") boolean strictJsonSchema,
            @ConfigProperty(name = "llm.runtime-default.tool-memory.max-tokens", defaultValue = "12000") int memoryMaxTokens) {
        Map<String, OpenAiChatModel> runtimeModels = runtimeModels(runtimes, defaultBaseUrl, defaultApiKey, defaultModel, temperature, maxTokens, timeoutMs, thinkingOffEnabled, strictJsonSchema);
        return new ExecutionRuntimeAgentFactory(runtimes, runtimeModels, mcpClientFactory, toolProfiles, personas, tokenCountEstimator, memoryMaxTokens);
    }

    private static Map<String, OpenAiChatModel> runtimeModels(
            ExecutionRuntimeRegistry runtimes,
            String defaultBaseUrl,
            String defaultApiKey,
            String defaultModel,
            double temperature,
            int maxTokens,
            long timeoutMs,
            boolean thinkingOffEnabled,
            boolean strictJsonSchema) {
        LinkedHashMap<String, OpenAiChatModel> models = new LinkedHashMap<>();
        for (ExecutionRuntimeDefinition runtime : runtimes.allRuntimes()) {
            InferenceLaneDefinition lane = runtimes.laneRequired(runtime.laneId());
            models.put(runtime.id(), chatModel(
                    firstNonBlank(lane.endpoint(), defaultBaseUrl),
                    firstNonBlank(resolveLaneApiKey(lane), defaultApiKey),
                    selectedModel(runtime, lane, defaultModel),
                    temperature,
                    maxTokens,
                    timeoutMs,
                    thinkingOffEnabled,
                    strictJsonSchema));
        }
        return Map.copyOf(models);
    }

    public static String selectedModel(ExecutionRuntimeDefinition runtime, InferenceLaneDefinition lane, String globalFallback) {
        return firstNonBlank(runtime == null ? null : runtime.model(), firstNonBlank(lane == null ? null : lane.model(), globalFallback));
    }

    private static OpenAiChatModel chatModel(String baseUrl, String apiKey, String model, double temperature, int maxTokens, long timeoutMs, boolean thinkingOffEnabled, boolean strictJsonSchema) {
        var builder = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .strictJsonSchema(strictJsonSchema)
                .timeout(Duration.ofMillis(timeoutMs));
        if (thinkingOffEnabled) {
            builder.customParameters(Map.of("chat_template_kwargs", Map.of("enable_thinking", false)));
        }
        return builder.build();
    }

    private static String resolveLaneApiKey(InferenceLaneDefinition lane) {
        String configured = null;
        if (!blank(lane.apiKeyConfigKey())) {
            configured = ConfigProvider.getConfig().getOptionalValue(lane.apiKeyConfigKey(), String.class).orElse(null);
        }
        if (!blank(configured)) {
            return configured;
        }
        return blank(lane.apiKeyEnvVariable()) ? null : System.getenv(lane.apiKeyEnvVariable());
    }

    private static String firstNonBlank(String first, String second) {
        return blank(first) ? second : first;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public static class ExecutionRuntimeAgentFactory implements AgentFactory {
        private final ExecutionRuntimeRegistry runtimes;
        private final Map<String, OpenAiChatModel> runtimeModels;
        private final McpClientFactory mcpClientFactory;
        private final ToolProfileRegistry toolProfiles;
        private final PersonaCatalog personas;
        private final TokenCountEstimator tokenCountEstimator;
        private final int memoryMaxTokens;
        private CamelContext camelContext;

        public ExecutionRuntimeAgentFactory(ExecutionRuntimeRegistry runtimes, Map<String, OpenAiChatModel> runtimeModels, McpClientFactory mcpClientFactory, ToolProfileRegistry toolProfiles, PersonaCatalog personas, TokenCountEstimator tokenCountEstimator, int memoryMaxTokens) {
            this.runtimes = runtimes;
            this.runtimeModels = runtimeModels;
            this.mcpClientFactory = mcpClientFactory;
            this.toolProfiles = toolProfiles;
            this.personas = personas;
            this.tokenCountEstimator = tokenCountEstimator;
            this.memoryMaxTokens = memoryMaxTokens;
        }

        @Override
        public Agent createAgent(Exchange exchange) {
            if (exchange == null) {
                throw new IllegalArgumentException("ExecutionRuntimeAgentFactory requires the active Camel exchange");
            }
            String runtimeId = exchange.getProperty("executionRuntimeId", String.class);
            if (blank(runtimeId)) {
                throw new IllegalStateException("No executionRuntimeId on exchange");
            }
            ExecutionRuntimeDefinition runtime = runtimes.getRequired(runtimeId);
            OpenAiChatModel model = runtimeModels.get(runtime.id());
            if (model == null) {
                throw new IllegalStateException("No chat model configured for execution runtime " + runtime.id());
            }
            AgentConfiguration configuration = new AgentConfiguration().withChatModel(model);
            if (runtime.toolCalling()) {
                configuration.withChatMemoryProvider(new EphemeralToolPassChatMemoryProvider(memoryMaxTokens, tokenCountEstimator, true));
                List<McpClient> clients = mcpClients(exchange);
                if (!clients.isEmpty()) {
                    configuration.withMcpClients(clients);
                }
            }
            return configuration.getChatMemoryProvider() == null ? new AgentWithoutMemory(configuration) : new AgentWithMemory(configuration);
        }

        private List<McpClient> mcpClients(Exchange exchange) {
            String personaId = exchange.getProperty("personaId", String.class);
            if (blank(personaId)) {
                throw new IllegalStateException("No personaId on exchange for tool-capable execution runtime");
            }
            String profileId = exchange.getProperty("toolProfile", "none", String.class);
            Persona persona = personas.getRequired(personaId);
            ToolProfile profile = toolProfiles.getRequired(profileId);
            return mcpClientFactory.clientsFor(persona, profile);
        }

        @Override public CamelContext getCamelContext() { return camelContext; }
        @Override public void setCamelContext(CamelContext camelContext) { this.camelContext = camelContext; }
    }
}
