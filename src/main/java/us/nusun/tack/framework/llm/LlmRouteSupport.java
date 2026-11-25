package us.nusun.tack.framework.llm;

import static us.nusun.tack.framework.model.FrameworkText.firstNonBlank;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Body;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangeProperty;
import org.apache.camel.ServiceStatus;
import org.apache.camel.component.langchain4j.agent.api.AiAgentBody;
import us.nusun.tack.framework.logging.LogTokens;
import us.nusun.tack.framework.model.LlmJobEnvelope;
import us.nusun.tack.framework.model.LlmJobResult;
import us.nusun.tack.framework.model.LlmJobStatus;
import us.nusun.tack.framework.projection.AgentRunProjectionManager;

import java.time.Instant;

@ApplicationScoped
public class LlmRouteSupport {
    private static final String REGISTERED = "llmReplyRegistered";

    public static final class LlmAvailabilityException extends RuntimeException {
        public LlmAvailabilityException(String message) { super(message); }
        public LlmAvailabilityException(String message, Throwable cause) { super(message, cause); }
    }

    public static final class LlmResponseException extends RuntimeException {
        public LlmResponseException(String message) { super(message); }
        public LlmResponseException(String message, Throwable cause) { super(message, cause); }
    }

    @Inject CamelContext camelContext;
    @Inject ObjectMapper objectMapper;
    @Inject InFlightReplyManager inFlightReplyManager;
    @Inject LlmDeadLetterFactory deadLetterFactory;
    @Inject LogTokens logTokens;
    @Inject AgentRunProjectionManager projections;

    public LlmRouteSupport() {
    }

    public LlmRouteSupport(
            CamelContext camelContext,
            InFlightReplyManager inFlightReplyManager,
            LlmDeadLetterFactory deadLetterFactory) {
        this(camelContext, inFlightReplyManager, deadLetterFactory, new LogTokens(false));
    }

    public LlmRouteSupport(
            CamelContext camelContext,
            InFlightReplyManager inFlightReplyManager,
            LlmDeadLetterFactory deadLetterFactory,
            LogTokens logTokens) {
        this.camelContext = camelContext;
        this.objectMapper = new ObjectMapper();
        this.inFlightReplyManager = inFlightReplyManager;
        this.deadLetterFactory = deadLetterFactory;
        this.logTokens = logTokens;
    }

    public void registerReply(@Body LlmJobEnvelope job, Exchange exchange) {
        inFlightReplyManager.register(job);
        exchange.setProperty(REGISTERED, true);
    }

    public void discardReply(Exchange exchange) {
        if (Boolean.TRUE.equals(exchange.removeProperty(REGISTERED))) {
            inFlightReplyManager.discard(LlmExecutionContext.require(exchange).jobId());
        }
    }

    public LlmJobResult awaitReply(Exchange exchange) throws Exception {
        long timeoutMs = Long.parseLong(exchange.getContext().resolvePropertyPlaceholders("{{llm.reply-timeout-ms:300000}}"));
        try {
            exchange.removeProperty(REGISTERED);
            return inFlightReplyManager.await(LlmExecutionContext.require(exchange).jobId(), timeoutMs);
        } catch (Exception failure) {
            throw new LlmAvailabilityException("LLM reply failure: " + deadLetterFactory.contextSummary(exchange)
                    + " reason=" + failure.getMessage(), failure);
        }
    }

    public void prepareAgentCall(@ExchangeProperty("llmJob") LlmJobEnvelope job, Exchange exchange) {
        exchange.setProperty("llmStartedAt", Instant.now());
        exchange.getIn().setBody(new AiAgentBody<>(job.prompt(), firstNonBlank(job.systemPrompt(), """
                You are a precise worker in a shared LLM queue. Return only what the requester asked for.
                """.strip()), job.jobId()));
    }

    public void logPrompt(@ExchangeProperty("llmJob") LlmJobEnvelope job, Exchange exchange) {
        if (projections != null) projections.recordLlmStarted(exchange);
        String systemPrompt = job == null ? null : job.systemPrompt();
        String prompt = job == null ? null : job.prompt();
        logTokens().print(logTokens().wrapDim(agentLabel(exchange), agentLogColor(exchange),
                "SYSTEM " + firstNonBlank(systemPrompt, "(default system prompt)") + "\nPROMPT " + prompt));
    }

    public void logModelResponse(@Body LlmJobResult result, Exchange exchange) {
        String content = result == null ? null : result.content();
        logTokens().print(logTokens().wrap(agentLabel(exchange), agentLogColor(exchange), "RESPONSE " + content));
    }

    public void requireAgentResponse(@Body String rawResponse, Exchange exchange) {
        if (rawResponse == null || rawResponse.isBlank()) {
            throw new LlmResponseException("LLM response failure: reason=empty-final-content " + agentResponseContext(exchange));
        }
    }

    public void validateResponseSchema(@Body LlmJobResult result, @ExchangeProperty("llmJob") LlmJobEnvelope job,
            Exchange exchange) throws Exception {
        if (result == null || result.status() != LlmJobStatus.SUCCEEDED || result.content() == null || result.content().isBlank()
                || job == null || !job.hasResponseSchema()) {
            return;
        }
        SchemaContract contract;
        try {
            contract = SchemaContract.resolve(exchange.getContext(), objectMapper, job.responseSchema());
        } catch (java.io.IOException | IllegalArgumentException failure) {
            throw new LlmResponseException("Unable to resolve response schema " + agentResponseContext(exchange), failure);
        }
        JsonNode response;
        try {
            response = SchemaContract.readJson(objectMapper, result.content());
        } catch (JsonProcessingException e) {
            throw new LlmResponseException(
                    "LLM response failure: reason=malformed-structured-output "
                            + agentResponseContext(exchange)
                            + " responseChars=" + result.content().length()
                            + " responseTail=" + responseTail(result.content()),
                    e);
        }
        try {
            contract.validate(response);
        } catch (IllegalArgumentException failure) {
            throw new LlmResponseException(failure.getMessage() + " " + agentResponseContext(exchange), failure);
        }
    }

    public LlmJobResult succeededResult(
            @Body String rawResponse,
            @ExchangeProperty("llmJob") LlmJobEnvelope job,
            @ExchangeProperty("llmStartedAt") Instant startedAt,
            Exchange exchange) {
        return LlmJobResult.succeeded(job, deadLetterFactory.modelId(exchange), rawResponse, rawResponse, startedAt);
    }

    public void verifySelectedRoutesAvailable(Exchange exchange) {
        String routeSuffix = routeSuffix(LlmExecutionContext.require(exchange).laneId());
        if (localWorkerRouteVerificationEnabled(exchange)) {
            verifyRouteStarted(exchange, "llm-worker-" + routeSuffix);
        }
        verifyRouteStarted(exchange, "llm-response-consumer-" + routeSuffix);
    }

    public static String routeSuffix(String laneId) {
        return laneId == null ? "" : laneId.replaceAll("[^A-Za-z0-9_-]", "-");
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

    private static String agentResponseContext(Exchange exchange) {
        LlmExecutionContext invocation = LlmExecutionContext.on(exchange);
        String diagnostics = exchange.getProperty(LlmCallDiagnostics.FINAL_SUMMARY, String.class);
        return (invocation == null ? "" : "jobId=" + invocation.jobId()
                + " operation=" + invocation.primitive() + "/" + invocation.primitiveId()
                + " lane=" + invocation.laneId() + " passType=" + invocation.passType())
                + " finishReason=" + header(exchange, "CamelLangChain4jAgentFinishReason")
                + " inputTokens=" + header(exchange, "CamelLangChain4jAgentInputTokenCount")
                + " outputTokens=" + header(exchange, "CamelLangChain4jAgentOutputTokenCount")
                + " totalTokens=" + header(exchange, "CamelLangChain4jAgentTotalTokenCount")
                + (diagnostics == null ? "" : " " + diagnostics);
    }

    private static String header(Exchange exchange, String name) {
        Object value = exchange.getMessage().getHeader(name);
        return value == null ? "unknown" : String.valueOf(value);
    }

    private static String responseTail(String content) {
        if (content == null) {
            return "<null>";
        }
        String compact = content.replaceAll("\\s+", " ").strip();
        int max = 240;
        String tail = compact.length() <= max ? compact : "..." + compact.substring(compact.length() - max);
        return "'" + tail.replace("'", "\\'") + "'";
    }

    private LogTokens logTokens() {
        if (logTokens == null) {
            logTokens = new LogTokens(false);
        }
        return logTokens;
    }

    private static String agentLabel(Exchange exchange) {
        var invocation = LlmExecutionContext.on(exchange);
        var job = exchange.getProperty("llmJob", LlmJobEnvelope.class);
        return invocation == null ? firstNonBlank(job == null ? null : job.requester(), "LLM") : invocation.logLabel();
    }

    private static String agentLogColor(Exchange exchange) {
        var invocation = LlmExecutionContext.on(exchange);
        return invocation == null ? null : invocation.logColor();
    }
}
