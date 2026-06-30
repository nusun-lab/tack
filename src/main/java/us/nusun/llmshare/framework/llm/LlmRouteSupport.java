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

    public LlmRouteSupport() {
    }

    public LlmRouteSupport(
            CamelContext camelContext,
            ExecutionRuntimeRegistry runtimes,
            InFlightReplyManager inFlightReplyManager,
            LlmDeadLetterFactory deadLetterFactory) {
        this.camelContext = camelContext;
        this.runtimes = runtimes;
        this.inFlightReplyManager = inFlightReplyManager;
        this.deadLetterFactory = deadLetterFactory;
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
        return shouldApplyProviderSchema(exchange, job, runtime)
                ? invocationUri + "&jsonSchema=" + job.responseSchema()
                : invocationUri;
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

    private boolean shouldApplyProviderSchema(Exchange exchange, LlmJobEnvelope job, ExecutionRuntimeDefinition runtime) {
        if (runtime.toolCalling() || job == null || job.responseSchema() == null || job.responseSchema().isBlank()) {
            return false;
        }
        if (Boolean.TRUE.equals(exchange.getProperty("llmForceProviderSchema", Boolean.class))) {
            return true;
        }
        return Boolean.parseBoolean(exchange.getContext().resolvePropertyPlaceholders("{{llm.runtime-default.structured-output.provider-schema-enabled:false}}"));
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

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
