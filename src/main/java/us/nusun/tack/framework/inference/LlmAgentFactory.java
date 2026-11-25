package us.nusun.tack.framework.inference;


import dev.langchain4j.data.message.ToolExecutionResultMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
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
import org.apache.camel.component.langchain4j.agent.api.AgentWithoutMemory;
import org.apache.camel.component.langchain4j.agent.api.CompositeToolProvider;
import org.eclipse.microprofile.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.nusun.tack.framework.inference.lane.EffectiveLaneSettings;
import us.nusun.tack.framework.inference.lane.LaneConfiguration;
import us.nusun.tack.framework.inference.lane.ModelConfiguration;
import us.nusun.tack.framework.inference.lane.ToolPassSettings;
import us.nusun.tack.framework.inference.lane.InferenceLaneRegistry;
import us.nusun.tack.framework.persona.PersonaCatalog;
import us.nusun.tack.framework.tool.ToolProfileRegistry;
import us.nusun.tack.framework.tool.mcp.McpClientFactory;
import us.nusun.tack.framework.inference.memory.QwenTokenCountEstimator;
import us.nusun.tack.framework.inference.memory.ToolLoopGuardChatMemory;
import us.nusun.tack.framework.inference.lane.InferenceLaneDefinition;
import us.nusun.tack.framework.tool.ToolProfile;
import us.nusun.tack.framework.sandbox.PersonaSandboxManager;
import us.nusun.tack.framework.run.MissionRosterService;
import us.nusun.tack.framework.run.MissionRosterTool;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            LlmRouteSupport routeSupport,
            TokenCountEstimator tokenCountEstimator,
            ObjectMapper objectMapper,
            ModelConfiguration defaults,
            LaneConfiguration laneConfig,
            Config config) {
        Map<String, ChatModel> models = new LinkedHashMap<>();
        Map<String, ToolPassSettings> toolPassSettings = new LinkedHashMap<>();
        for (InferenceLaneDefinition lane : lanes.allLanes()) {
            EffectiveLaneSettings settings = EffectiveLaneSettings.resolve(lane, defaults, laneConfig, config);
            models.put(lane.id(), chatModel(settings));
            toolPassSettings.put(lane.id(), settings.toolPass());
            LOG.info("Configured inference lane {} model={} maxOutputTokens={} temperature={}",
                    lane.id(), settings.model(), settings.toolPass().maxOutputTokens(), settings.temperature());
        }
        return new DynamicAgentFactory(models, toolPassSettings, mcpClientFactory, toolProfiles,
                personas, tokenCountEstimator, sandboxes, missionRoster, routeSupport, objectMapper);
    }

    private static OpenAiChatModel chatModel(EffectiveLaneSettings settings) {
        var builder = OpenAiChatModel.builder()
                .baseUrl(settings.baseUrl())
                .apiKey(settings.apiKey())
                .modelName(settings.model())
                .temperature(settings.temperature())
                .topP(settings.topP())
                .presencePenalty(settings.presencePenalty())
                .maxTokens(settings.toolPass().maxOutputTokens())
                .returnThinking(true)
                .sendThinking(true, "reasoning")
                .strictJsonSchema(settings.strictJsonSchema())
                .timeout(Duration.ofMillis(settings.timeoutMs()))
                .httpClientBuilder(http11ClientBuilder());
        if (!blank(settings.reasoningEffort())) {
            builder.reasoningEffort(settings.reasoningEffort().strip());
        }

        Map<String, Object> customParameters = new LinkedHashMap<>();
        customParameters.put("top_k", settings.topK());
        customParameters.put("min_p", settings.minP());
        customParameters.put("repetition_penalty", settings.repetitionPenalty());
        if (settings.thinkingOff()) {
            customParameters.put("chat_template_kwargs", Map.of("enable_thinking", false));
        }
        return builder.customParameters(customParameters).build();
    }

    private static JdkHttpClientBuilder http11ClientBuilder() {
        return JdkHttpClient.builder()
                .httpClientBuilder(
                        HttpClient.newBuilder()
                                .version(HttpClient.Version.HTTP_1_1));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public static class DynamicAgentFactory implements AgentFactory {
        private final Map<String, ChatModel> laneModels;
        private final Map<String, ToolPassSettings> toolPassSettings;
        private final McpClientFactory mcpClientFactory;
        private final ToolProfileRegistry toolProfiles;
        private final PersonaCatalog personas;
        private final PersonaSandboxManager sandboxes;
        private final MissionRosterService missionRoster;
        private final LlmRouteSupport routeSupport;
        private final ObjectMapper objectMapper;
        private final TokenCountEstimator tokenCountEstimator;
        private CamelContext camelContext;

        public DynamicAgentFactory(Map<String, ChatModel> laneModels, Map<String, ToolPassSettings> toolPassSettings,
                                   McpClientFactory mcpClientFactory, ToolProfileRegistry toolProfiles, PersonaCatalog personas,
                                   TokenCountEstimator tokenCountEstimator, PersonaSandboxManager sandboxes,
                                   MissionRosterService missionRoster, LlmRouteSupport routeSupport, ObjectMapper objectMapper) {
            this.laneModels = Map.copyOf(laneModels);
            this.toolPassSettings = Map.copyOf(toolPassSettings);
            this.mcpClientFactory = mcpClientFactory;
            this.toolProfiles = toolProfiles;
            this.personas = personas;
            this.tokenCountEstimator = tokenCountEstimator;
            this.sandboxes = sandboxes;
            this.missionRoster = missionRoster;
            this.routeSupport = routeSupport;
            this.objectMapper = objectMapper;
        }

        @Override
        public Agent createAgent(Exchange exchange) {
            if (exchange == null) {
                throw new IllegalArgumentException("DynamicAgentFactory requires the active Camel exchange");
            }
            LlmExecutionContext invocation = LlmExecutionContext.require(exchange);
            String laneId = invocation.laneId();
            ToolPassSettings settings = toolPassSettings.get(laneId);
            ChatModel model = laneModels.get(laneId);
            if (model == null) {
                throw new IllegalStateException("No chat model configured for inference lane " + laneId);
            }
            LlmPassType passType = invocation.passType();
            LlmCallDiagnostics diagnostics = passType == LlmPassType.ACTION
                    ? new LlmCallDiagnostics(exchange, tokenCountEstimator, settings.contextWindow(), settings.maxOutputTokens(), settings.memoryTokens())
                    : null;
            ChatModel dispatchObservedModel = new DispatchObservingChatModel(model, exchange, routeSupport, diagnostics);
            AgentConfiguration configuration = new AgentConfiguration().withChatModel(dispatchObservedModel);
            List<ToolProvider> mcpTools = List.of();
            if (passType == LlmPassType.ACTION) {
                configuration.withHallucinatedToolNameStrategy(HALLUCINATED_TOOL_NAME_RECOVERY_STRATEGY)
                        .withToolArgumentsErrorHandler(ToolErrorFeedback::arguments)
                        .withToolExecutionErrorHandler(ToolErrorFeedback::execution);
                if (settings.maxToolCalls() > 0) {
                    configuration.withMaxToolCallingRoundTrips(settings.maxToolCalls() + 1);
                }
                configuration.withChatMemoryProvider(id -> ToolLoopGuardChatMemory.forPass(id, settings, tokenCountEstimator));
                ToolProfile profile = toolProfiles.getRequired(invocation.toolProfile());
                String personaId = invocation.personaId();
                if (blank(personaId)) {
                    throw new IllegalStateException("No personaId on exchange for tool-capable LLM pass");
                }
                mcpTools = mcpClientFactory.toolProvidersFor(personas.getRequired(personaId), profile);
                List<Object> customTools = new ArrayList<>();
                String missionId = invocation.missionId();
                if (missionRoster != null && !blank(missionId)) {
                    Integer capacityMaximum = invocation.capacityMaximum();
                    customTools.add(new MissionRosterTool(missionRoster, missionId, capacityMaximum));
                }
                if (sandboxes != null && profile.allowsTool(SANDBOX_TOOL_SERVER, SANDBOX_EXEC_TOOL)) {
                    String sandboxName = invocation.sandboxName();
                    if (blank(sandboxName)) {
                        sandboxName = sandboxes.sandboxNameFor(missionId, personaId);
                    }
                    var sandboxTool = sandboxes.tool(sandboxName);
                    if (sandboxTool != null) {
                        customTools.add(sandboxTool);
                        LOG.info("Registered sandbox_exec for profile={} sandbox={} persona={} mission={}", profile.id(), sandboxName,
                                personaId, missionId);
                    }
                }
                if (!customTools.isEmpty()) {
                    configuration.withCustomTools(customTools);
                }
            }
            AbstractAgent<?> agent = configuration.getChatMemoryProvider() == null
                    ? new AgentWithoutMemory(configuration)
                    : new AgentWithMemory(configuration);
            applyProviderSchema(exchange, invocation, agent);
            if (diagnostics == null) {
                return agent;
            }
            List<ToolProvider> invocationTools = mcpTools;
            return (body, tools) -> {
                var result = agent.chat(body, combineTools(tools, invocationTools));
                diagnostics.capture(result);
                return result;
            };
        }

        static ChatRequest forceInitialToolChoice(ChatRequest request) {
            boolean hasToolResult = request.messages().stream().anyMatch(ToolExecutionResultMessage.class::isInstance);
            return request.toBuilder().toolChoice(hasToolResult ? ToolChoice.AUTO : ToolChoice.REQUIRED).build();
        }

        private static final class DispatchObservingChatModel implements ChatModel {
            private final ChatModel delegate;
            private final Exchange exchange;
            private final LlmRouteSupport routeSupport;
            private final LlmCallDiagnostics diagnostics;
            private boolean dispatched;

            private DispatchObservingChatModel(ChatModel delegate, Exchange exchange, LlmRouteSupport routeSupport,
                                               LlmCallDiagnostics diagnostics) {
                this.delegate = delegate;
                this.exchange = exchange;
                this.routeSupport = routeSupport;
                this.diagnostics = diagnostics;
            }

            @Override
            public ChatResponse chat(ChatRequest request, ChatRequestOptions options) {
                if (!dispatched && routeSupport != null) {
                    routeSupport.logPrompt(job(exchange), exchange);
                    dispatched = true;
                }
                if (diagnostics != null) {
                    request = forceInitialToolChoice(request);
                    diagnostics.logBudget(request);
                }
                return delegate.chat(request, options);
            }

            @Override
            public dev.langchain4j.model.chat.request.ChatRequestParameters defaultRequestParameters() {
                return delegate.defaultRequestParameters();
            }

            @Override
            public List<dev.langchain4j.model.chat.listener.ChatModelListener> listeners() {
                return delegate.listeners();
            }

            @Override
            public dev.langchain4j.model.ModelProvider provider() {
                return delegate.provider();
            }

            @Override
            public java.util.Set<dev.langchain4j.model.chat.Capability> supportedCapabilities() {
                return delegate.supportedCapabilities();
            }
        }

        private static ToolProvider combineTools(ToolProvider routeTools, List<ToolProvider> mcpTools) {
            if (mcpTools.isEmpty()) {
                return routeTools;
            }
            if (routeTools == null && mcpTools.size() == 1) {
                return mcpTools.getFirst();
            }
            List<ToolProvider> providers = new ArrayList<>();
            if (routeTools != null) {
                providers.add(routeTools);
            }
            providers.addAll(mcpTools);
            return new CompositeToolProvider(providers);
        }

        private void applyProviderSchema(Exchange exchange, LlmExecutionContext invocation, AbstractAgent<?> agent) {
            LlmJobEnvelope job = job(exchange);
            if (!job.hasResponseSchema() || !invocation.providerSchemaEnabled()) {
                return;
            }
            try {
                agent.setResponseFormat(SchemaContract.resolve(exchange.getContext(), objectMapper, job.responseSchema()).providerFormat());
                LOG.info("Provider schema enforcement applied jobId={} lane={} passType={}",
                        invocation.jobId(), invocation.laneId(), invocation.passType());
            } catch (IOException | IllegalArgumentException failure) {
                throw new IllegalArgumentException("Unable to resolve responseSchema for provider structured output: jobId="
                        + invocation.jobId() + " lane=" + invocation.laneId() + " passType=" + invocation.passType(), failure);
            }
        }

        private static LlmJobEnvelope job(Exchange exchange) {
            return exchange.getProperty("llmJob", LlmJobEnvelope.class);
        }

        @Override public CamelContext getCamelContext() { return camelContext; }
        @Override public void setCamelContext(CamelContext camelContext) { this.camelContext = camelContext; }
    }
}
