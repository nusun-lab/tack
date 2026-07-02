package us.nusun.llmshare.framework.llm;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Body;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangeProperty;
import org.apache.camel.ServiceStatus;
import org.apache.camel.component.langchain4j.agent.api.AiAgentBody;
import us.nusun.llmshare.framework.inventory.ExecutionRuntimeRegistry;
import us.nusun.llmshare.framework.logging.LogTokens;
import us.nusun.llmshare.framework.model.ExecutionRuntimeDefinition;
import us.nusun.llmshare.framework.model.InferenceLaneDefinition;
import us.nusun.llmshare.framework.model.LlmJobEnvelope;
import us.nusun.llmshare.framework.model.LlmJobResult;

import java.time.Instant;
import java.util.UUID;

@ApplicationScoped
public class LlmRouteSupport {
    private static final String DEFAULT_SYSTEM_PROMPT = "You are a precise worker in a shared LLM queue. Return only what the requester asked for.";

    public static final class LlmAvailabilityException extends RuntimeException {
        public LlmAvailabilityException(String message) { super(message); }
        public LlmAvailabilityException(String message, Throwable cause) { super(message, cause); }
    }

    @Inject CamelContext camelContext;
    @Inject ExecutionRuntimeRegistry runtimes;
    @Inject InFlightReplyManager inFlightReplyManager;
    @Inject LlmDeadLetterFactory deadLetterFactory;
    @Inject LogTokens logTokens;

    public LlmRouteSupport() {
    }

    public LlmRouteSupport(
            CamelContext camelContext,
            ExecutionRuntimeRegistry runtimes,
            InFlightReplyManager inFlightReplyManager,
            LlmDeadLetterFactory deadLetterFactory) {
        this(camelContext, runtimes, inFlightReplyManager, deadLetterFactory, new LogTokens(false));
    }

    public LlmRouteSupport(
            CamelContext camelContext,
            ExecutionRuntimeRegistry runtimes,
            InFlightReplyManager inFlightReplyManager,
            LlmDeadLetterFactory deadLetterFactory,
            LogTokens logTokens) {
        this.camelContext = camelContext;
        this.runtimes = runtimes;
        this.inFlightReplyManager = inFlightReplyManager;
        this.deadLetterFactory = deadLetterFactory;
        this.logTokens = logTokens;
    }

    public LlmJobResult awaitReply(@ExchangeProperty("llmJobId") UUID jobId, Exchange exchange) throws Exception {
        long timeoutMs = Long.parseLong(exchange.getContext().resolvePropertyPlaceholders("{{llm.reply-timeout-ms:300000}}"));
        try {
            return inFlightReplyManager.await(jobId, timeoutMs);
        } catch (Exception failure) {
            throw new LlmAvailabilityException("LLM reply failure: " + deadLetterFactory.contextSummary(exchange)
                    + " reason=" + failure.getMessage(), failure);
        }
    }

    public void prepareAgentCall(@ExchangeProperty("llmJob") LlmJobEnvelope job, Exchange exchange) {
        ExecutionRuntimeDefinition runtime = runtimes.getRequired(job.requester());
        exchange.setProperty("executionRuntime", runtime);
        exchange.setProperty("executionRuntimeId", runtime.id());
        exchange.setProperty("llmStartedAt", Instant.now());
        exchange.setProperty("agentInvocationUri", agentInvocationUri(runtime, job, exchange));
        exchange.getIn().setBody(new AiAgentBody<>(job.prompt(), firstNonBlank(job.systemPrompt(), DEFAULT_SYSTEM_PROMPT), job.jobId()));
    }

    public void logPrompt(@ExchangeProperty("llmJob") LlmJobEnvelope job, Exchange exchange) {
        String prompt = job == null ? null : job.prompt();
        logTokens().print(logTokens().wrapDim(agentLabel(exchange), agentLogColor(exchange), "PROMPT " + prompt));
    }

    public void logModelResponse(@Body LlmJobResult result, Exchange exchange) {
        String content = result == null ? null : result.content();
        logTokens().print(logTokens().wrap(agentLabel(exchange), agentLogColor(exchange), "RESPONSE " + content));
    }

    public LlmJobResult succeededResult(
            @Body String rawResponse,
            @ExchangeProperty("llmJob") LlmJobEnvelope job,
            @ExchangeProperty("llmStartedAt") Instant startedAt,
            Exchange exchange) {
        return LlmJobResult.succeeded(job, deadLetterFactory.modelId(exchange), rawResponse, rawResponse, startedAt);
    }

    public void verifySelectedRoutesAvailable(@ExchangeProperty("inferenceLane") InferenceLaneDefinition lane, Exchange exchange) {
        if (lane == null) {
            throw new LlmAvailabilityException("Selected LLM inference lane is unavailable: " + deadLetterFactory.contextSummary(exchange)
                    + " reason=missing inferenceLane exchange property");
        }
        String routeSuffix = routeSuffix(lane.id());
        if (localWorkerRouteVerificationEnabled(exchange)) {
            verifyRouteStarted(exchange, "llm-worker-" + routeSuffix);
        }
        verifyRouteStarted(exchange, "llm-response-consumer-" + routeSuffix);
    }

    public static String routeSuffix(String laneId) {
        return laneId == null ? "" : laneId.replaceAll("[^A-Za-z0-9_-]", "-");
    }

    private String agentInvocationUri(ExecutionRuntimeDefinition runtime, LlmJobEnvelope job, Exchange exchange) {
        String invocationUri = "langchain4j-agent:" + runtime.agentName() + agentConfigurationQuery(runtime);
        return usesAgentFactory(runtime) || !ProviderStructuredOutputSupport.shouldApplyProviderSchema(exchange, job)
                ? invocationUri
                : invocationUri + "&jsonSchema=" + job.responseSchema();
    }

    private boolean usesAgentFactory(ExecutionRuntimeDefinition runtime) {
        return runtime.agentFactoryBeanName() != null && !runtime.agentFactoryBeanName().isBlank();
    }

    private String agentConfigurationQuery(ExecutionRuntimeDefinition runtime) {
        if (runtime.agentFactoryBeanName() != null && !runtime.agentFactoryBeanName().isBlank()) {
            return "?agentFactory=#" + runtime.agentFactoryBeanName();
        }
        if (runtime.agentConfigurationBeanName() != null && !runtime.agentConfigurationBeanName().isBlank()) {
            return "?agentConfiguration=#" + runtime.agentConfigurationBeanName();
        }
        throw new IllegalStateException("Execution runtime " + runtime.id() + " does not define an agent factory or agent configuration bean");
    }

    private void verifyRouteStarted(Exchange exchange, String routeId) {
        ServiceStatus status = camelContext.getRouteController().getRouteStatus(routeId);
        if (status == null || !status.isStarted()) {
            throw new LlmAvailabilityException("LLM route '" + routeId + "' is not running. Refusing to queue request: "
                    + deadLetterFactory.contextSummary(exchange) + " reason=route status is " + status);
        }
    }

    private boolean localWorkerRouteVerificationEnabled(Exchange exchange) {
        return Boolean.parseBoolean(exchange.getContext().resolvePropertyPlaceholders("{{llm.local-worker-route-verification.enabled:true}}"));
    }

    private LogTokens logTokens() {
        if (logTokens == null) {
            logTokens = new LogTokens(false);
        }
        return logTokens;
    }

    private static String agentLabel(Exchange exchange) {
        String label = exchange.getProperty("agentLabel", String.class);
        if (label != null && !label.isBlank()) {
            return label;
        }
        LlmJobEnvelope job = exchange.getProperty("llmJob", LlmJobEnvelope.class);
        return job == null || job.requester() == null || job.requester().isBlank() ? "LLM" : job.requester();
    }

    private static String agentLogColor(Exchange exchange) {
        return exchange.getProperty("agentLogColor", String.class);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
