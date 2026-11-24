package us.nusun.tack.framework.llm;

import static us.nusun.tack.framework.model.FrameworkText.firstNonBlank;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.tool.ToolProvider;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.component.langchain4j.agent.api.AbstractAgent;
import org.apache.camel.component.langchain4j.agent.api.Agent;
import org.apache.camel.component.langchain4j.agent.api.AgentConfiguration;
import org.apache.camel.component.langchain4j.agent.api.AgentFactory;
import org.apache.camel.component.langchain4j.agent.api.AgentWithMemory;
import org.apache.camel.component.langchain4j.agent.api.AiAgentWithMemoryService;
import org.apache.camel.component.langchain4j.agent.api.AgentWithoutMemory;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.nusun.tack.framework.inventory.InferenceLaneRegistry;
import us.nusun.tack.framework.inventory.PersonaCatalog;
import us.nusun.tack.framework.inventory.ToolProfileRegistry;
import us.nusun.tack.framework.logging.LogTokens;
import us.nusun.tack.framework.mcp.McpClientFactory;
import us.nusun.tack.framework.memory.QwenTokenCountEstimator;
import us.nusun.tack.framework.memory.EphemeralToolPassChatMemoryProvider;
import us.nusun.tack.framework.model.InferenceLaneDefinition;
import us.nusun.tack.framework.model.LlmJobEnvelope;
import us.nusun.tack.framework.model.LlmPassType;
import us.nusun.tack.framework.model.Persona;
import us.nusun.tack.framework.model.RoutineDefinition;
import us.nusun.tack.framework.model.ToolProfile;
import us.nusun.tack.framework.sandbox.PersonaSandboxManager;
import us.nusun.tack.framework.service.MissionRosterService;
import us.nusun.tack.framework.tool.MissionRosterTool;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Singleton
public class LlmAgentFactory {
    private static final Logger LOG = LoggerFactory.getLogger(LlmAgentFactory.class);
    private static final HallucinatedToolNameRecoveryStrategy HALLUCINATED_TOOL_NAME_RECOVERY_STRATEGY =
            new HallucinatedToolNameRecoveryStrategy();
    private static final String SANDBOX_TOOL_SERVER = "sandbox";
    private static final String SANDBOX_EXEC_TOOL = "sandbox_exec";

    @Produces
    @Singleton
    TokenCountEstimator toolPassTokenCountEstimator() {
        return new QwenTokenCountEstimator();
    }

    @Produces
    @Singleton
    @Named("dynamicAgentFactory")
    AgentFactory dynamicAgentFactory(
            InferenceLaneRegistry lanes,
            McpClientFactory mcpClientFactory,
            ToolProfileRegistry toolProfiles,
            PersonaCatalog personas,
            PersonaSandboxManager sandboxes,
            MissionRosterService missionRoster,
            LogTokens logTokens,
            LlmRouteSupport routeSupport,
            TokenCountEstimator tokenCountEstimator,
            @ConfigProperty(name = "llm.model-default.openai.base-url") String defaultBaseUrl,
            @ConfigProperty(name = "llm.model-default.openai.api-key") String defaultApiKey,
            @ConfigProperty(name = "llm.model-default.openai.model") String defaultModel,
            @ConfigProperty(name = "llm.model-default.openai.temperature") double temperature,
            @ConfigProperty(name = "llm.model-default.openai.top-p") double topP,
            @ConfigProperty(name = "llm.model-default.openai.presence-penalty") double presencePenalty,
            @ConfigProperty(name = "llm.model-default.openai.top-k") int topK,
            @ConfigProperty(name = "llm.model-default.openai.min-p") double minP,
            @ConfigProperty(name = "llm.model-default.openai.repetition-penalty") double repetitionPenalty,
            @ConfigProperty(name = "llm.model-default.openai.max-tokens") int maxTokens,
            @ConfigProperty(name = "llm.model-default.openai.timeout-ms") long timeoutMs,
            @ConfigProperty(name = "llm.model-default.openai.thinking-off.enabled") boolean thinkingOffEnabled,
            @ConfigProperty(name = "llm.model-default.openai.reasoning-effort") Optional<String> reasoningEffort,
            @ConfigProperty(name = "llm.model-default.openai.strict-json-schema", defaultValue = "true") boolean strictJsonSchema,
            @ConfigProperty(name = "llm.model-default.tool-memory.max-tokens", defaultValue = "12000") int memoryMaxTokens,
            @ConfigProperty(name = "llm.model-default.max-tool-calling-round-trips", defaultValue = "4") int maxToolCallingRoundTrips) {
        Map<String, ChatModel> laneModels = laneModels(
                lanes,
                defaultBaseUrl,
                defaultApiKey,
                defaultModel,
                temperature,
                topP,
                presencePenalty,
                topK,
                minP,
                repetitionPenalty,
                maxTokens,
                timeoutMs,
                thinkingOffEnabled,
                reasoningEffort.orElse(null),
                strictJsonSchema);
        return new DynamicAgentFactory(lanes, laneModels, mcpClientFactory, toolProfiles, personas, tokenCountEstimator,
                memoryMaxTokens, maxToolCallingRoundTrips, sandboxes, logTokens, missionRoster, routeSupport);
    }

    private static Map<String, ChatModel> laneModels(
            InferenceLaneRegistry lanes,
            String defaultBaseUrl,
            String defaultApiKey,
            String defaultModel,
            double temperature,
            double topP,
            double presencePenalty,
            int topK,
            double minP,
            double repetitionPenalty,
            int maxTokens,
            long timeoutMs,
            boolean thinkingOffEnabled,
            String reasoningEffort,
            boolean strictJsonSchema) {
        LinkedHashMap<String, ChatModel> models = new LinkedHashMap<>();
        for (InferenceLaneDefinition lane : lanes.allLanes()) {
            String configPrefix = laneConfigPrefix(lane);
            double laneTemperature = laneDouble(configPrefix, "temperature", temperature);
            double laneTopP = laneDouble(configPrefix, "top-p", topP);
            double lanePresencePenalty = laneDouble(configPrefix, "presence-penalty", presencePenalty);
            int laneTopK = laneInt(configPrefix, "top-k", topK);
            double laneMinP = laneDouble(configPrefix, "min-p", minP);
            double laneRepetitionPenalty = laneDouble(configPrefix, "repetition-penalty", repetitionPenalty);
            int laneMaxTokens = laneInt(configPrefix, "max-tokens", maxTokens);
            String laneReasoningEffort = laneString(configPrefix, "reasoning-effort", reasoningEffort);
            String model = selectedModel(lane, defaultModel);
            LOG.info("Configured inference lane {} model={} reasoningEffort={} maxOutputTokens={} sampling temperature={} topP={} presencePenalty={} topK={} minP={} repetitionPenalty={}",
                    lane.id(), model, blank(laneReasoningEffort) ? "provider-default" : laneReasoningEffort, laneMaxTokens,
                    laneTemperature, laneTopP, lanePresencePenalty, laneTopK, laneMinP, laneRepetitionPenalty);
            models.put(lane.id(), chatModel(
                    firstNonBlank(lane.endpoint(), defaultBaseUrl),
                    firstNonBlank(resolveLaneApiKey(lane), defaultApiKey),
                    model,
                    laneTemperature,
                    laneTopP,
                    lanePresencePenalty,
                    laneTopK,
                    laneMinP,
                    laneRepetitionPenalty,
                    laneMaxTokens,
                    timeoutMs,
                    thinkingOffEnabled,
                    laneReasoningEffort,
                    strictJsonSchema));
        }
        return Map.copyOf(models);
    }

    public static String selectedModel(InferenceLaneDefinition lane, String globalFallback) {
        return firstNonBlank(lane == null ? null : lane.model(), globalFallback);
    }

    private static OpenAiChatModel chatModel(
            String baseUrl,
            String apiKey,
            String model,
            double temperature,
            double topP,
            double presencePenalty,
            int topK,
            double minP,
            double repetitionPenalty,
            int maxTokens,
            long timeoutMs,
            boolean thinkingOffEnabled,
            String reasoningEffort,
            boolean strictJsonSchema) {
        var builder = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(temperature)
                .topP(topP)
                .presencePenalty(presencePenalty)
                .maxTokens(maxTokens)
                .returnThinking(true)
                .sendThinking(true, "reasoning")
                .strictJsonSchema(strictJsonSchema)
                .timeout(Duration.ofMillis(timeoutMs))
                .httpClientBuilder(http11ClientBuilder());
        if (!blank(reasoningEffort)) {
            builder.reasoningEffort(reasoningEffort.strip());
        }

        Map<String, Object> customParameters = new LinkedHashMap<>();
        customParameters.put("top_k", topK);
        customParameters.put("min_p", minP);
        customParameters.put("repetition_penalty", repetitionPenalty);
        if (thinkingOffEnabled) {
            customParameters.put("chat_template_kwargs", Map.of("enable_thinking", false));
        }
        builder.customParameters(customParameters);
        return builder.build();
    }

    private static JdkHttpClientBuilder http11ClientBuilder() {
        return JdkHttpClient.builder()
                .httpClientBuilder(
                        HttpClient.newBuilder()
                                .version(HttpClient.Version.HTTP_1_1));
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

    private static String laneConfigPrefix(InferenceLaneDefinition lane) {
        String configKey = lane == null ? null : lane.apiKeyConfigKey();
        if (blank(configKey)) {
            return null;
        }
        int separator = configKey.lastIndexOf('.');
        return separator <= 0 ? null : configKey.substring(0, separator);
    }

    private static double laneDouble(String configPrefix, String property, double fallback) {
        if (blank(configPrefix)) {
            return fallback;
        }
        return ConfigProvider.getConfig()
                .getOptionalValue(configPrefix + "." + property, Double.class)
                .orElse(fallback);
    }

    private static int laneInt(String configPrefix, String property, int fallback) {
        if (blank(configPrefix)) {
            return fallback;
        }
        return ConfigProvider.getConfig()
                .getOptionalValue(configPrefix + "." + property, Integer.class)
                .orElse(fallback);
    }

    private static String laneString(String configPrefix, String property, String fallback) {
        if (blank(configPrefix)) {
            return fallback;
        }
        return ConfigProvider.getConfig()
                .getOptionalValue(configPrefix + "." + property, String.class)
                .filter(value -> !blank(value))
                .orElse(fallback);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public static class DynamicAgentFactory implements AgentFactory {
        private final InferenceLaneRegistry lanes;
        private final Map<String, ChatModel> laneModels;
        private final McpClientFactory mcpClientFactory;
        private final ToolProfileRegistry toolProfiles;
        private final PersonaCatalog personas;
        private final PersonaSandboxManager sandboxes;
        private final LogTokens logTokens;
        private final MissionRosterService missionRoster;
        private final LlmRouteSupport routeSupport;
        private static final int DEFAULT_MAX_TOOL_CALLING_ROUND_TRIPS = 4;

        private final TokenCountEstimator tokenCountEstimator;
        private final int memoryMaxTokens;
        private final int maxToolCallingRoundTrips;
        private CamelContext camelContext;

        public DynamicAgentFactory(InferenceLaneRegistry lanes, Map<String, ChatModel> laneModels, McpClientFactory mcpClientFactory, ToolProfileRegistry toolProfiles, PersonaCatalog personas, TokenCountEstimator tokenCountEstimator, int memoryMaxTokens) {
            this(lanes, laneModels, mcpClientFactory, toolProfiles, personas, tokenCountEstimator, memoryMaxTokens,
                    DEFAULT_MAX_TOOL_CALLING_ROUND_TRIPS, null, null, null, null);
        }

        public DynamicAgentFactory(InferenceLaneRegistry lanes, Map<String, ChatModel> laneModels, McpClientFactory mcpClientFactory, ToolProfileRegistry toolProfiles, PersonaCatalog personas, TokenCountEstimator tokenCountEstimator, int memoryMaxTokens, int maxToolCallingRoundTrips) {
            this(lanes, laneModels, mcpClientFactory, toolProfiles, personas, tokenCountEstimator, memoryMaxTokens,
                    maxToolCallingRoundTrips, null, null, null, null);
        }

        public DynamicAgentFactory(InferenceLaneRegistry lanes, Map<String, ChatModel> laneModels, McpClientFactory mcpClientFactory,
                                   ToolProfileRegistry toolProfiles, PersonaCatalog personas, TokenCountEstimator tokenCountEstimator,
                                   int memoryMaxTokens, int maxToolCallingRoundTrips, PersonaSandboxManager sandboxes) {
            this(lanes, laneModels, mcpClientFactory, toolProfiles, personas, tokenCountEstimator, memoryMaxTokens,
                    maxToolCallingRoundTrips, sandboxes, null, null, null);
        }

        public DynamicAgentFactory(InferenceLaneRegistry lanes, Map<String, ChatModel> laneModels, McpClientFactory mcpClientFactory,
                                   ToolProfileRegistry toolProfiles, PersonaCatalog personas, TokenCountEstimator tokenCountEstimator,
                                   int memoryMaxTokens, int maxToolCallingRoundTrips, PersonaSandboxManager sandboxes, LogTokens logTokens) {
            this(lanes, laneModels, mcpClientFactory, toolProfiles, personas, tokenCountEstimator, memoryMaxTokens,
                    maxToolCallingRoundTrips, sandboxes, logTokens, null, null);
        }

        public DynamicAgentFactory(InferenceLaneRegistry lanes, Map<String, ChatModel> laneModels, McpClientFactory mcpClientFactory,
                                   ToolProfileRegistry toolProfiles, PersonaCatalog personas, TokenCountEstimator tokenCountEstimator,
                                   int memoryMaxTokens, int maxToolCallingRoundTrips, PersonaSandboxManager sandboxes, LogTokens logTokens,
                                   MissionRosterService missionRoster, LlmRouteSupport routeSupport) {
            this.lanes = lanes;
            this.laneModels = laneModels;
            this.mcpClientFactory = mcpClientFactory;
            this.toolProfiles = toolProfiles;
            this.personas = personas;
            this.tokenCountEstimator = tokenCountEstimator;
            this.memoryMaxTokens = memoryMaxTokens;
            this.maxToolCallingRoundTrips = maxToolCallingRoundTrips;
            this.sandboxes = sandboxes;
            this.logTokens = logTokens;
            this.missionRoster = missionRoster;
            this.routeSupport = routeSupport;
        }

        @Override
        public Agent createAgent(Exchange exchange) {
            if (exchange == null) {
                throw new IllegalArgumentException("DynamicAgentFactory requires the active Camel exchange");
            }
            String laneId = firstNonBlank(exchange.getProperty("laneId", String.class), job(exchange) == null ? null : job(exchange).laneId(), RoutineDefinition.FRAMEWORK_DEFAULT_LANE);
            InferenceLaneDefinition lane = lanes.getRequired(laneId);
            String configPrefix = laneConfigPrefix(lane);
            int laneMemoryMaxTokens = laneInt(configPrefix, "tool-memory.max-tokens", memoryMaxTokens);
            int laneMaxToolCalls = laneInt(configPrefix, "max-tool-calling-round-trips", maxToolCallingRoundTrips);
            double laneCompactionThreshold = laneDouble(configPrefix, "tool-memory.compaction-threshold",
                    ConfigProvider.getConfig().getOptionalValue("llm.model-default.tool-memory.compaction-threshold", Double.class).orElse(0.50));
            int laneRecentToolInteractions = laneInt(configPrefix, "tool-memory.recent-tool-interactions",
                    ConfigProvider.getConfig().getOptionalValue("llm.model-default.tool-memory.recent-tool-interactions", Integer.class).orElse(3));
            int laneContextWindow = laneInt(configPrefix, "context-window",
                    ConfigProvider.getConfig().getOptionalValue("llm.model-default.openai.context-window", Integer.class).orElse(69632));
            int laneMaxOutputTokens = laneInt(configPrefix, "max-tokens",
                    ConfigProvider.getConfig().getOptionalValue("llm.model-default.openai.max-tokens", Integer.class).orElse(4092));
            ChatModel model = laneModels.get(laneId);
            if (model == null) {
                throw new IllegalStateException("No chat model configured for inference lane " + laneId);
            }
            ChatModel dispatchObservedModel = new DispatchObservingChatModel(model, exchange, routeSupport);
            LlmPassType passType = passType(exchange);
            AgentConfiguration configuration = new AgentConfiguration().withChatModel(dispatchObservedModel);
            if (passType == LlmPassType.ACTION) {
                configuration.withChatMemoryProvider(new EphemeralToolPassChatMemoryProvider(
                        laneMemoryMaxTokens,
                        tokenCountEstimator,
                        true,
                        laneMaxToolCalls,
                        laneCompactionThreshold,
                        laneRecentToolInteractions,
                        ignored -> { }));
                String profileId = exchange.getProperty("toolProfile", "none", String.class);
                ToolProfile profile = toolProfiles.getRequired(profileId);
                List<McpClient> clients = mcpClients(exchange);
                if (!clients.isEmpty()) {
                    configuration.withMcpClients(clients);
                }
                LlmJobEnvelope job = job(exchange);
                List<Object> customTools = new ArrayList<>();
                String missionId = metadata(job, "missionId");
                if (missionRoster != null && !blank(missionId)) {
                    Integer capacityMaximum = integerProperty(exchange, "agentRunCapacityMaximum");
                    customTools.add(new MissionRosterTool(missionRoster, missionId, capacityMaximum));
                }
                if (sandboxes != null && profile.allowsTool(SANDBOX_TOOL_SERVER, SANDBOX_EXEC_TOOL)) {
                    String sandboxName = exchange.getProperty(PersonaSandboxManager.WORKING_STATE_SANDBOX_NAME, String.class);
                    if (blank(sandboxName)) {
                        String personaId = firstNonBlank(exchange.getProperty("personaId", String.class), metadata(job, "personaId"));
                        sandboxName = sandboxes.sandboxNameFor(missionId, personaId);
                    }
                    var sandboxTool = sandboxes.tool(sandboxName);
                    if (sandboxTool != null) {
                        customTools.add(sandboxTool);
                        LOG.info("Registered sandbox_exec for profile={} sandbox={} persona={} mission={}", profile.id(), sandboxName,
                                firstNonBlank(exchange.getProperty("personaId", String.class), metadata(job, "personaId")),
                                missionId);
                    }
                }
                if (!customTools.isEmpty()) {
                    configuration.withCustomTools(customTools);
                }
            }
            Agent agent = configuration.getChatMemoryProvider() == null
                    ? new AgentWithoutMemory(configuration)
                    : new TackToolAgent(configuration, laneMaxToolCalls, laneContextWindow, laneMaxOutputTokens,
                            laneMemoryMaxTokens, tokenCountEstimator, exchange);
            applyProviderSchemaIfRequired(exchange, laneId, passType, agent);
            return agent;
        }

        static ChatRequest forceInitialToolChoice(ChatRequest request) {
            return forceInitialToolChoice(request, null);
        }

        private static ChatRequest forceInitialToolChoice(ChatRequest request, ResponseFormat responseFormat) {
            boolean hasToolResult = request.messages().stream()
                    .anyMatch(ToolExecutionResultMessage.class::isInstance);
            ChatRequest.Builder builder = request.toBuilder()
                    .toolChoice(hasToolResult ? ToolChoice.AUTO : ToolChoice.REQUIRED);
            if (responseFormat != null) {
                builder.responseFormat(responseFormat);
            }
            return builder.build();
        }

        private static final class DispatchObservingChatModel implements ChatModel {
            private final ChatModel delegate;
            private final Exchange exchange;
            private final LlmRouteSupport routeSupport;

            private DispatchObservingChatModel(ChatModel delegate, Exchange exchange, LlmRouteSupport routeSupport) {
                this.delegate = delegate;
                this.exchange = exchange;
                this.routeSupport = routeSupport;
            }

            @Override
            public ChatResponse chat(ChatRequest request, ChatRequestOptions options) {
                observeDispatch(exchange, routeSupport);
                return delegate.chat(request, options);
            }
        }

        private static void observeDispatch(Exchange exchange, LlmRouteSupport routeSupport) {
            if (routeSupport == null || Boolean.TRUE.equals(exchange.getProperty("llmDispatchObserved", Boolean.class))) {
                return;
            }
            routeSupport.logPrompt(job(exchange), exchange);
            exchange.setProperty("llmDispatchObserved", true);
        }

        private static final class TackToolAgent extends AgentWithMemory {
            private final int maxToolCallingRoundTrips;
            private final int contextWindow;
            private final int maxOutputTokens;
            private final int transcriptMaxTokens;
            private final TokenCountEstimator tokenCountEstimator;
            private final Exchange exchange;
            private long cumulativeEstimatedInputTokens;

            private TackToolAgent(
                    AgentConfiguration configuration,
                    int maxToolCallingRoundTrips,
                    int contextWindow,
                    int maxOutputTokens,
                    int transcriptMaxTokens,
                    TokenCountEstimator tokenCountEstimator,
                    Exchange exchange) {
                super(configuration);
                this.maxToolCallingRoundTrips = maxToolCallingRoundTrips;
                this.contextWindow = contextWindow;
                this.maxOutputTokens = maxOutputTokens;
                this.transcriptMaxTokens = transcriptMaxTokens;
                this.tokenCountEstimator = tokenCountEstimator;
                this.exchange = exchange;
            }

            @Override
            public Result<String> chat(org.apache.camel.component.langchain4j.agent.api.AiAgentBody<?> aiAgentBody,
                    ToolProvider toolProvider) {
                Result<String> result = super.chat(aiAgentBody, toolProvider);
                captureDiagnostics(result);
                return result;
            }

            private void captureDiagnostics(Result<String> result) {
                var finalResponse = result == null ? null : result.finalResponse();
                var aiMessage = finalResponse == null ? null : finalResponse.aiMessage();
                String text = aiMessage == null ? null : aiMessage.text();
                String thinking = aiMessage == null ? null : aiMessage.thinking();

                exchange.setProperty("llmFinalTextChars", text == null ? 0 : text.length());
                exchange.setProperty("llmFinalThinkingChars", thinking == null ? 0 : thinking.length());
                exchange.setProperty("llmIntermediateResponses",
                        result == null || result.intermediateResponses() == null ? 0 : result.intermediateResponses().size());
                exchange.setProperty("llmToolExecutions",
                        result == null || result.toolExecutions() == null ? 0 : result.toolExecutions().size());

                if (finalResponse != null && finalResponse.tokenUsage() != null) {
                    exchange.setProperty("llmFinalInputTokens", finalResponse.tokenUsage().inputTokenCount());
                    exchange.setProperty("llmFinalOutputTokens", finalResponse.tokenUsage().outputTokenCount());
                    exchange.setProperty("llmFinalTotalTokens", finalResponse.tokenUsage().totalTokenCount());
                    if (finalResponse.tokenUsage() instanceof OpenAiTokenUsage openAiTokenUsage
                            && openAiTokenUsage.outputTokensDetails() != null) {
                        exchange.setProperty("llmFinalReasoningTokens",
                                openAiTokenUsage.outputTokensDetails().reasoningTokens());
                    }
                }

                LOG.info("LLM final response diagnostics: lane={} passType={} textChars={} thinkingChars={} "
                                + "finalInputTokens={} finalOutputTokens={} finalReasoningTokens={} "
                                + "intermediateResponses={} toolExecutions={}",
                        exchange.getProperty("laneId"),
                        exchange.getProperty("passType"),
                        exchange.getProperty("llmFinalTextChars"),
                        exchange.getProperty("llmFinalThinkingChars"),
                        exchange.getProperty("llmFinalInputTokens"),
                        exchange.getProperty("llmFinalOutputTokens"),
                        exchange.getProperty("llmFinalReasoningTokens"),
                        exchange.getProperty("llmIntermediateResponses"),
                        exchange.getProperty("llmToolExecutions"));
            }

            @Override
            protected void configureBuilder(AiServices<AiAgentWithMemoryService> builder, ToolProvider toolProvider) {
                super.configureBuilder(builder, toolProvider);
                builder.hallucinatedToolNameStrategy(HALLUCINATED_TOOL_NAME_RECOVERY_STRATEGY);
                builder.toolArgumentsErrorHandler(ToolErrorFeedback::arguments);
                builder.toolExecutionErrorHandler(ToolErrorFeedback::execution);
                if (maxToolCallingRoundTrips > 0) {
                    builder.maxToolCallingRoundTrips(maxToolCallingRoundTrips + 1);
                }
                ResponseFormat responseFormat = getResponseFormat();
                builder.chatRequestTransformer(request -> {
                    ChatRequest transformed = forceInitialToolChoice(request, responseFormat);
                    logTokenBudget(transformed);
                    return transformed;
                });
            }

            private void logTokenBudget(ChatRequest request) {
                int promptTokens = 0;
                int messageTokens = 0;
                int thinkingTokens = 0;
                int toolCallTokens = 0;
                int toolResultTokens = 0;
                boolean firstUser = true;

                for (ChatMessage message : request.messages()) {
                    if (message instanceof SystemMessage || (firstUser && message instanceof UserMessage)) {
                        promptTokens += tokenCountEstimator.estimateTokenCountInMessage(message);
                        if (message instanceof UserMessage) firstUser = false;
                    } else if (message instanceof UserMessage) {
                        firstUser = false;
                        messageTokens += tokenCountEstimator.estimateTokenCountInMessage(message);
                    } else if (message instanceof AiMessage ai) {
                        messageTokens += tokenCountEstimator.estimateTokenCountInText(ai.text());
                        thinkingTokens += tokenCountEstimator.estimateTokenCountInText(ai.thinking());
                        if (ai.hasToolExecutionRequests()) {
                            for (var call : ai.toolExecutionRequests()) {
                                toolCallTokens += tokenCountEstimator.estimateTokenCountInText(call.name());
                                toolCallTokens += tokenCountEstimator.estimateTokenCountInText(call.arguments());
                            }
                        }
                    } else if (message instanceof ToolExecutionResultMessage) {
                        toolResultTokens += tokenCountEstimator.estimateTokenCountInMessage(message);
                    }
                }

                int toolSchemaTokens = request.toolSpecifications().stream()
                        .map(ToolSpecification::toJson)
                        .mapToInt(tokenCountEstimator::estimateTokenCountInText)
                        .sum();
                int responseTokens = request.responseFormat() == null
                        ? 0 : tokenCountEstimator.estimateTokenCountInText(String.valueOf(request.responseFormat()));
                int transcriptTokens = messageTokens + thinkingTokens + toolCallTokens + toolResultTokens;
                int inputTokens = promptTokens + toolSchemaTokens + responseTokens + transcriptTokens;
                boolean compacted = request.messages().stream()
                        .filter(UserMessage.class::isInstance)
                        .map(UserMessage.class::cast)
                        .anyMatch(message -> "tack-runtime".equals(message.name()));
                int inputCeiling = Math.max(0, contextWindow - maxOutputTokens);
                cumulativeEstimatedInputTokens += inputTokens;

                String label = firstNonBlank(exchange.getProperty("agentLabel", String.class), "LLM");
                LOG.info("[{}] TOKEN BUDGET lane={} input={}/{} ({}%) | prompt={} tools={} response={} transcript={}/{} ({}%) "
                                + "| transcriptParts messages={} thinking={} toolCalls={} toolResults={} | compacted={} cumulativeInput={}",
                        label,
                        exchange.getProperty("laneId"),
                        inputTokens,
                        inputCeiling,
                        percent(inputTokens, inputCeiling),
                        promptTokens,
                        toolSchemaTokens,
                        responseTokens,
                        transcriptTokens,
                        transcriptMaxTokens,
                        percent(transcriptTokens, transcriptMaxTokens),
                        messageTokens,
                        thinkingTokens,
                        toolCallTokens,
                        toolResultTokens,
                        compacted,
                        cumulativeEstimatedInputTokens);
            }

            private static int percent(long used, long max) {
                return max <= 0 ? 0 : (int) Math.round((double) used / (double) max * 100.0);
            }
        }

        private List<McpClient> mcpClients(Exchange exchange) {
            String personaId = exchange.getProperty("personaId", String.class);
            if (blank(personaId)) {
                throw new IllegalStateException("No personaId on exchange for tool-capable LLM pass");
            }
            String profileId = exchange.getProperty("toolProfile", "none", String.class);
            Persona persona = personas.getRequired(personaId);
            ToolProfile profile = toolProfiles.getRequired(profileId);
            return mcpClientFactory.clientsFor(persona, profile);
        }

        private void applyProviderSchemaIfRequired(Exchange exchange, String laneId, LlmPassType passType, Agent agent) {
            LlmJobEnvelope job = exchange.getProperty("llmJob", LlmJobEnvelope.class);
            boolean schemaPresent = ProviderStructuredOutputSupport.hasResponseSchema(job);
            boolean schemaEnabled = ProviderStructuredOutputSupport.providerSchemaEnabled(exchange, job);
            if (!schemaPresent || !schemaEnabled) {
                if (schemaPresent) {
                    LOG.info("Provider schema enforcement skipped jobId={} lane={} passType={} schemaEnabled=false",
                            jobId(job), laneId, passType);
                } else {
                    LOG.debug("Provider schema enforcement skipped jobId={} lane={} passType={} schemaEnabled={} schemaPresent=false",
                            jobId(job), laneId, passType, schemaEnabled);
                }
                return;
            }
            if (!(agent instanceof AbstractAgent<?> abstractAgent)) {
                throw new IllegalStateException("Provider structured output requires a Camel AbstractAgent for lane "
                        + laneId + " passType=" + passType);
            }
            try {
                abstractAgent.setResponseFormat(ProviderStructuredOutputSupport.responseFormat(camelContext(exchange), job.responseSchema()));
                LOG.info("Provider schema enforcement applied jobId={} lane={} passType={} schemaEnabled=true",
                        jobId(job), laneId, passType);
            } catch (IOException e) {
                throw new IllegalArgumentException("Unable to resolve responseSchema for provider structured output: jobId="
                        + jobId(job) + " lane=" + laneId + " passType=" + passType, e);
            }
        }

        private CamelContext camelContext(Exchange exchange) {
            return camelContext == null ? exchange.getContext() : camelContext;
        }

        private static LlmJobEnvelope job(Exchange exchange) {
            return exchange.getProperty("llmJob", LlmJobEnvelope.class);
        }

        private static LlmPassType passType(Exchange exchange) {
            Object value = exchange.getProperty("passType");
            if (value instanceof LlmPassType passType) {
                return passType;
            }
            if (value instanceof String text && !text.isBlank()) {
                return LlmPassType.valueOf(text.strip().toUpperCase());
            }
            LlmJobEnvelope job = job(exchange);
            return job == null || job.passType() == null ? LlmPassType.SYNTHESIS : job.passType();
        }

        private static Integer integerProperty(Exchange exchange, String key) {
            if (exchange == null || blank(key)) {
                return null;
            }
            Object value = exchange.getProperty(key);
            if (value == null) {
                value = exchange.getIn().getHeader(key);
            }
            if (value == null) {
                return null;
            }
            if (value instanceof Number number) {
                return number.intValue();
            }
            String text = String.valueOf(value);
            return text.isBlank() ? null : Integer.parseInt(text.trim());
        }

        private static String metadata(LlmJobEnvelope job, String key) {
            if (job == null || job.metadata() == null) {
                return null;
            }
            Object value = job.metadata().get(key);
            return value == null ? null : String.valueOf(value);
        }

        private static Object jobId(LlmJobEnvelope job) {
            return job == null ? null : job.jobId();
        }

        @Override public CamelContext getCamelContext() { return camelContext; }
        @Override public void setCamelContext(CamelContext camelContext) { this.camelContext = camelContext; }
    }
}
