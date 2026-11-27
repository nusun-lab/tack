package us.nusun.tack.framework.inference;

import static us.nusun.tack.framework.FrameworkText.firstNonBlank;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.Body;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangeProperty;
import org.apache.camel.ServiceStatus;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.component.kafka.consumer.KafkaManualCommit;
import org.apache.camel.component.langchain4j.agent.api.AiAgentBody;
import org.jboss.logging.Logger;
import us.nusun.tack.framework.logging.LogTokens;
import us.nusun.tack.framework.run.AgentRunProjectionManager;

import java.time.Instant;

@ApplicationScoped
public class LlmRouteSupport {
    private static final Logger LOG = Logger.getLogger(LlmRouteSupport.class);
    private static final String REGISTERED = "llmReplyRegistered";

    public static final class LlmAvailabilityException extends RuntimeException {
        public LlmAvailabilityException(String message) { super(message); }
        public LlmAvailabilityException(String message, Throwable cause) { super(message, cause); }
    }

    public static final class LlmResponseException extends RuntimeException {
        public LlmResponseException(String message) { super(message); }
        public LlmResponseException(String message, Throwable cause) { super(message, cause); }
    }

    private final ObjectMapper objectMapper;
    private final InFlightReplyManager inFlightReplyManager;
    private final LlmDeadLetterFactory deadLetterFactory;
    private final LogTokens logTokens;
    private final AgentRunProjectionManager projections;

    public LlmRouteSupport(ObjectMapper objectMapper, InFlightReplyManager inFlightReplyManager,
                           LlmDeadLetterFactory deadLetterFactory, LogTokens logTokens,
                           AgentRunProjectionManager projections) {
        this.objectMapper = objectMapper;
        this.inFlightReplyManager = inFlightReplyManager;
        this.deadLetterFactory = deadLetterFactory;
        this.logTokens = logTokens;
        this.projections = projections;
    }

    public void registerReply(Exchange exchange) {
        var invocation = LlmExecutionContext.require(exchange);
        inFlightReplyManager.register(invocation.jobId(), invocation.agentRunId());
        exchange.setProperty(REGISTERED, true);
    }

    public void discardReply(Exchange exchange) {
        if (Boolean.TRUE.equals(exchange.removeProperty(REGISTERED))) {
            inFlightReplyManager.discard(LlmExecutionContext.require(exchange).jobId());
        }
    }

    public LlmJobResult awaitReply(Exchange exchange) throws Exception {
        long timeoutMs = Long.parseLong(exchange.getContext().resolvePropertyPlaceholders("{{llm.reply-timeout-ms:900000}}"));
        var invocation = LlmExecutionContext.require(exchange);
        try {
            exchange.removeProperty(REGISTERED);
            return inFlightReplyManager.await(invocation.jobId(), timeoutMs);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new LlmAvailabilityException("LLM reply failure: " + deadLetterFactory.contextSummary(exchange)
                    + " reason=" + firstNonBlank(failure.getMessage(), failure.getClass().getSimpleName()), failure);
        }
    }

    public void commitWorkerRequest(Exchange exchange) {
        var commit = exchange.getMessage().getHeader(KafkaConstants.MANUAL_COMMIT, KafkaManualCommit.class);
        try {
            if (commit == null) {
                throw new IllegalStateException("Kafka worker requires a synchronous offset commit");
            }
            // Commit while this consumer still owns the partition, before entering the side-effecting tool loop.
            commit.commit();
        } catch (RuntimeException failure) {
            LOG.warnf(failure, "LLM execution rejected: %s reason=offset commit failed", workerContext(exchange));
            throw failure;
        }
    }

    public boolean claimWorkerJob(Exchange exchange) {
        var job = exchange.getProperty("llmJob", LlmJobEnvelope.class);
        if (!inFlightReplyManager.claim(job.jobId())) {
            LOG.warnf("LLM execution rejected: %s reason=duplicate or no waiting owner", workerContext(exchange));
            return false;
        }
        LOG.infof("LLM execution started: %s", workerContext(exchange));
        return true;
    }

    public void completeWorkerJob(Exchange exchange) {
        var body = exchange.getMessage().getBody();
        LlmJobResult result = body instanceof LlmJobResult completed ? completed : deadLetterFactory.failedLlmResult(exchange);
        exchange.getMessage().setBody(result);
        inFlightReplyManager.complete(result);
        if (result.status() == LlmJobStatus.FAILED) {
            LOG.warnf("LLM execution failed: %s reason=%s", workerContext(exchange), result.error());
        } else {
            LOG.infof("LLM execution completed: %s", workerContext(exchange));
        }
    }

    private String workerContext(Exchange exchange) {
        return deadLetterFactory.contextSummary(exchange)
                + " topic=" + header(exchange, KafkaConstants.TOPIC)
                + " partition=" + header(exchange, KafkaConstants.PARTITION)
                + " offset=" + header(exchange, KafkaConstants.OFFSET);
    }

    public AiAgentBody<?> agentBody(@ExchangeProperty("llmJob") LlmJobEnvelope job) {
        return new AiAgentBody<>(job.prompt(), firstNonBlank(job.systemPrompt(), """
                You are a precise worker in a shared LLM queue. Return only what the requester asked for.
                """.strip()), job.jobId());
    }

    public void logPrompt(LlmJobEnvelope job, Exchange exchange) {
        projections.recordLlmStarted(exchange);
        var invocation = LlmExecutionContext.require(exchange);
        logTokens.print(logTokens.wrapDim(invocation.logLabel(), invocation.logColor(),
                "SYSTEM " + firstNonBlank(job.systemPrompt(), "(default system prompt)") + "\nPROMPT " + job.prompt()));
    }

    public void logModelResponse(@Body LlmJobResult result, Exchange exchange) {
        var invocation = LlmExecutionContext.require(exchange);
        logTokens.print(logTokens.wrap(invocation.logLabel(), invocation.logColor(), "RESPONSE " + result.content()));
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
        verifyRouteStarted(exchange, "llm-worker-" + routeSuffix);
    }

    public static String routeSuffix(String laneId) {
        return laneId == null ? "" : laneId.replaceAll("[^A-Za-z0-9_-]", "-");
    }

    private void verifyRouteStarted(Exchange exchange, String routeId) {
        ServiceStatus status = exchange.getContext().getRouteController().getRouteStatus(routeId);
        if (status == null || !status.isStarted()) {
            throw new LlmAvailabilityException("LLM route '" + routeId + "' is not running. Refusing to queue request: "
                    + deadLetterFactory.contextSummary(exchange) + " reason=route status is " + status);
        }
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
}
