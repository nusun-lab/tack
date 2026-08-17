package us.nusun.llmshare.framework.llm;

import static us.nusun.llmshare.framework.model.FrameworkText.firstNonBlank;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Body;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangeProperty;
import org.apache.camel.ServiceStatus;
import org.apache.camel.component.langchain4j.agent.api.AiAgentBody;
import us.nusun.llmshare.framework.inventory.InferenceLaneRegistry;
import us.nusun.llmshare.framework.logging.LogTokens;
import us.nusun.llmshare.framework.model.InferenceLaneDefinition;
import us.nusun.llmshare.framework.model.LlmJobEnvelope;
import us.nusun.llmshare.framework.model.LlmJobResult;

import java.time.Instant;
import java.util.UUID;

@ApplicationScoped
public class LlmRouteSupport {
    private static final String DYNAMIC_AGENT_URI = "langchain4j-agent:dynamic?agentFactory=#dynamicAgentFactory";

    public static final class LlmAvailabilityException extends RuntimeException {
        public LlmAvailabilityException(String message) { super(message); }
        public LlmAvailabilityException(String message, Throwable cause) { super(message, cause); }
    }

    @Inject CamelContext camelContext;
    @Inject InferenceLaneRegistry lanes;
    @Inject InFlightReplyManager inFlightReplyManager;
    @Inject LlmDeadLetterFactory deadLetterFactory;
    @Inject LogTokens logTokens;

    public LlmRouteSupport() {
    }

    public LlmRouteSupport(
            CamelContext camelContext,
            InferenceLaneRegistry lanes,
            InFlightReplyManager inFlightReplyManager,
            LlmDeadLetterFactory deadLetterFactory) {
        this(camelContext, lanes, inFlightReplyManager, deadLetterFactory, new LogTokens(false));
    }

    public LlmRouteSupport(
            CamelContext camelContext,
            InferenceLaneRegistry lanes,
            InFlightReplyManager inFlightReplyManager,
            LlmDeadLetterFactory deadLetterFactory,
            LogTokens logTokens) {
        this.camelContext = camelContext;
        this.lanes = lanes;
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
        InferenceLaneDefinition lane = lanes.getRequired(job.laneId());
        exchange.setProperty("inferenceLane", lane);
        exchange.setProperty("laneId", lane.id());
        exchange.setProperty("passType", job.passType());
        exchange.setProperty("llmStartedAt", Instant.now());
        exchange.setProperty("agentInvocationUri", agentInvocationUri(job));
        exchange.getIn().setBody(new AiAgentBody<>(job.prompt(), firstNonBlank(job.systemPrompt(), """
                You are a precise worker in a shared LLM queue. Return only what the requester asked for.
                """.strip()), job.jobId()));
    }

    public void logPrompt(@ExchangeProperty("llmJob") LlmJobEnvelope job, Exchange exchange) {
        String systemPrompt = job == null ? null : job.systemPrompt();
        String prompt = job == null ? null : job.prompt();
        logTokens().print(logTokens().wrapDim(agentLabel(exchange), agentLogColor(exchange),
                "SYSTEM " + firstNonBlank(systemPrompt, "(default system prompt)") + "\nPROMPT " + prompt));
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

    private static String agentInvocationUri(LlmJobEnvelope job) {
        String tags = metadataText(job, "camelToolTags");
        if (tags == null) {
            return DYNAMIC_AGENT_URI;
        }
        if (!tags.matches("[A-Za-z0-9_-]+(?:,[A-Za-z0-9_-]+)*")) {
            throw new IllegalArgumentException("Invalid Camel tool tags on LLM job: " + tags);
        }
        return DYNAMIC_AGENT_URI + "&tags=" + tags;
    }

    private static String metadataText(LlmJobEnvelope job, String key) {
        if (job == null || job.metadata() == null) {
            return null;
        }
        Object value = job.metadata().get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).strip();
        return text.isBlank() ? null : text;
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
}
