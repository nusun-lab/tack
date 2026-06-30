package us.nusun.llmshare.framework.route;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.ServiceStatus;
import org.apache.camel.ValidationException;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jackson.JacksonDataFormat;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.component.jsonvalidator.JsonValidationException;
import org.apache.camel.component.langchain4j.agent.api.AiAgentBody;
import us.nusun.llmshare.framework.inventory.PersonaCatalog;
import us.nusun.llmshare.framework.llm.InFlightReplyManager;
import us.nusun.llmshare.framework.llm.LlmDeadLetterFactory;
import us.nusun.llmshare.framework.llm.LlmJobFactory;
import us.nusun.llmshare.framework.model.ExecutionRuntimeDefinition;
import us.nusun.llmshare.framework.inventory.ExecutionRuntimeRegistry;
import us.nusun.llmshare.framework.model.InferenceLaneDefinition;
import us.nusun.llmshare.framework.model.LlmJobDeadLetter;
import us.nusun.llmshare.framework.model.LlmJobEnvelope;
import us.nusun.llmshare.framework.model.LlmJobRequest;
import us.nusun.llmshare.framework.model.LlmJobResult;

import java.time.Instant;
import java.util.UUID;

@ApplicationScoped
public class LlmRoute extends RouteBuilder {
    private static final String DEFAULT_SYSTEM_PROMPT = "You are a precise worker in a shared LLM queue. Return only what the requester asked for.";

    public static final class LlmAvailabilityException extends RuntimeException {
        public LlmAvailabilityException(String message) { super(message); }
        public LlmAvailabilityException(String message, Throwable cause) { super(message, cause); }
    }

    @Inject CamelContext camelContext;
    @Inject ExecutionRuntimeRegistry runtimes;
    @Inject PersonaCatalog personas;
    @Inject InFlightReplyManager inFlightReplyManager;
    @Inject LlmJobFactory jobFactory;
    @Inject LlmDeadLetterFactory deadLetterFactory;

    @Override
    public void configure() {
        onException(JsonValidationException.class, ValidationException.class)
                .handled(false)
                .logStackTrace(false)
                .logExhausted(false)
                .log(LoggingLevel.WARN, "LLM contract validation failed: ${exception.message}");

        onException(LlmAvailabilityException.class)
                .handled(false)
                .logStackTrace(false)
                .logExhausted(false)
                .log(LoggingLevel.WARN, "${exception.message}");

        onCompletion()
                .onWhen(exchangeProperty("llmQueueFailureHandling").isEqualTo(true))
                .onFailureOnly()
                .setProperty("llmFailureMessage", simple("${exception.message}"))
                .setProperty("llmFailureContext", method(this, "contextSummary"))
                .log(LoggingLevel.WARN, "LLM queue failure: ${exchangeProperty.llmFailureContext} reason=${exchangeProperty.llmFailureMessage}")
                .bean("inFlightReplyManager", "discard(${exchangeProperty.llmJobId})")
                .choice()
                    .when(exchangeProperty("llmJob").isNotNull())
                        .wireTap("direct:publish-failed-llm-job-dlq")
                .end();

        from("direct:llm")
                .routeId("llm")
                .setProperty("llmQueueFailureHandling", constant(true))
                .bean(this, "prepareLlmJob")
                .bean("inFlightReplyManager", "register")
                .log(LoggingLevel.INFO, "PROMPT ${body.prompt}")
                .bean(this, "verifySelectedLlmRoutesAvailable")
                .marshal(jacksonDataFormat(LlmJobEnvelope.class))
                .setHeader(KafkaConstants.KEY, method(this, "requestKafkaKey"))
                .toD("language:simple:kafka:${exchangeProperty.laneRequestTopic}")
                .bean(this, "awaitLlmReply")
                .log(LoggingLevel.INFO, "RESPONSE ${body.content}")
                .choice()
                    .when(simple("${body.status} == 'SUCCEEDED' && ${body.content} != null && ${body.content} != '' && ${exchangeProperty.llmJob.responseSchema} != null && ${exchangeProperty.llmJob.responseSchema} != ''"))
                        .setProperty("llmResult", body())
                        .doTry()
                            .setBody(simple("${body.content}"))
                            .toD("json-validator:${exchangeProperty.llmJob.responseSchema}")
                        .doFinally()
                            .setBody(exchangeProperty("llmResult"))
                        .end()
                .end()
                .validate(body().isInstanceOf(LlmJobResult.class));

        for (InferenceLaneDefinition lane : runtimes.allLanes()) {
            llmWorker(lane);
        }

        from("direct:handle-llm-response")
                .routeId("handle-llm-response")
                .validate(body().isInstanceOf(LlmJobResult.class))
                .bean("inFlightReplyManager", "complete");

        from("direct:publish-failed-llm-job-dlq")
                .routeId("publish-failed-llm-job-dlq")
                .setBody(method(this, "failedQueuedJobDeadLetter"))
                .log(LoggingLevel.WARN, "Publishing failed LLM job to DLQ: jobId=${body.jobId} lane=${body.laneId} runtime=${body.runtimeId} reason=${body.failureMessage}")
                .marshal(jacksonDataFormat(LlmJobDeadLetter.class))
                .setHeader(KafkaConstants.KEY, method(this, "jobIdKafkaKey"))
                .toD("kafka:{{llm.topic.dead-letter:llm.dead-letter}}");

        from("direct:call-llm-agent")
                .routeId("call-llm-agent")
                .bean(this, "restoreFrameworkMetadata")
                .setProperty("executionRuntime", method(this, "requiredRuntime(${exchangeProperty.llmJob.requester})"))
                .setProperty("executionRuntimeId", simple("${exchangeProperty.executionRuntime.id}"))
                .setProperty("llmStartedAt", method(this, "now"))
                .setProperty("applyProviderSchema", method(this, "shouldApplyProviderSchema"))
                .setProperty("agentInvocationUri", method(this, "agentInvocationUri(${exchangeProperty.executionRuntime})"))
                .setBody(method(this, "agentBody(${exchangeProperty.llmJob})"))
                .choice()
                    .when(simple("${exchangeProperty.applyProviderSchema} == true"))
                        .toD("${exchangeProperty.agentInvocationUri}&jsonSchema=${exchangeProperty.llmJob.responseSchema}")
                    .otherwise()
                        .toD("${exchangeProperty.agentInvocationUri}")
                .end()
                .validate(body().isNotNull())
                .setBody(method(this, "succeededLlmResult"));

        from("direct:build-failed-llm-result")
                .routeId("build-failed-llm-result")
                .setBody(method(this, "failedLlmResult"));

    }

    private void llmWorker(InferenceLaneDefinition lane) {
        String laneId = lane.id();
        String requestTopic = lane.requestTopic();
        String replyTopic = lane.responseTopic();
        String routeSuffix = laneId.replaceAll("[^A-Za-z0-9_-]", "-");
        String groupSuffix = routeSuffix.isBlank() ? "lane" : routeSuffix;
        from("kafka:" + requestTopic
                + "?groupId={{llm.consumer.group:llm-workers}}." + groupSuffix
                + "&consumersCount=" + lane.parallelism()
                + "&maxPollRecords=1"
                + "&maxPollIntervalMs=900000"
                + "&autoOffsetReset=earliest")
                .routeId("llm-worker-" + routeSuffix)
                .autoStartup("{{llm.worker.enabled:true}}")
                .unmarshal(jacksonDataFormat(LlmJobEnvelope.class))
                .setProperty("llmJob", body())
                .bean(this, "restoreFrameworkMetadata")
                .setProperty("agentLabel", simple("${body.requester}"))
                .doTry()
                    .to("direct:call-llm-agent")
                .doCatch(Exception.class)
                    .setProperty("llmFailureMessage", simple("${exception.message}"))
                    .wireTap("direct:publish-failed-llm-job-dlq")
                    .to("direct:build-failed-llm-result")
                .end()
                .marshal(jacksonDataFormat(LlmJobResult.class))
                .setHeader(KafkaConstants.KEY, method(this, "jobIdKafkaKey"))
                .to("kafka:" + replyTopic);

        from("kafka:" + replyTopic
                + "?groupId={{llm.response.consumer.group}}-" + laneId
                + "&autoOffsetReset=latest"
                + "&maxPollRecords=1")
                .routeId("llm-response-consumer-" + routeSuffix)
                .unmarshal(jacksonDataFormat(LlmJobResult.class))
                .to("direct:handle-llm-response");
    }

    private static JacksonDataFormat jacksonDataFormat(Class<?> type) {
        JacksonDataFormat dataFormat = new JacksonDataFormat(type);
        dataFormat.setModuleClassNames("com.fasterxml.jackson.datatype.jsr310.JavaTimeModule");
        return dataFormat;
    }

    public void prepareLlmJob(Exchange exchange) {
        jobs().prepare(
                exchange,
                runtimes,
                personas,
                exchange.getContext().resolvePropertyPlaceholders("{{agent.framework.default-synthesis-runtime:synthesis-low}}"),
                globalModelFallback(exchange));
    }

    public ExecutionRuntimeDefinition requiredRuntime(String runtimeId) {
        return runtimes.getRequired(runtimeId);
    }

    public Instant now() {
        return Instant.now();
    }

    public Object agentBody(LlmJobEnvelope job) {
        return new AiAgentBody<>(job.prompt(), firstNonBlank(job.systemPrompt(), DEFAULT_SYSTEM_PROMPT), job.jobId());
    }

    public String agentInvocationUri(ExecutionRuntimeDefinition runtime) {
        String invocationUri = "langchain4j-agent:" + runtime.agentName();
        if (runtime.agentFactoryBeanName() != null && !runtime.agentFactoryBeanName().isBlank()) {
            return invocationUri + "?agentFactory=#" + runtime.agentFactoryBeanName();
        }
        if (runtime.agentConfigurationBeanName() != null && !runtime.agentConfigurationBeanName().isBlank()) {
            return invocationUri + "?agentConfiguration=#" + runtime.agentConfigurationBeanName();
        }
        throw new IllegalStateException("Execution runtime " + runtime.id() + " does not define an agent factory or agent configuration bean");
    }

    public LlmJobResult succeededLlmResult(Exchange exchange) {
        LlmJobEnvelope job = exchange.getProperty("llmJob", LlmJobEnvelope.class);
        Instant startedAt = exchange.getProperty("llmStartedAt", Instant.class);
        String rawResponse = exchange.getIn().getBody(String.class);
        return LlmJobResult.succeeded(job, modelId(exchange), rawResponse, rawResponse, startedAt);
    }

    public LlmJobResult failedLlmResult(Exchange exchange) {
        return deadLetters().failedLlmResult(exchange, globalModelFallback(exchange));
    }

    public LlmJobResult awaitLlmReply(Exchange exchange) throws Exception {
        UUID jobId = exchange.getProperty("llmJobId", UUID.class);
        long timeoutMs = Long.parseLong(exchange.getContext().resolvePropertyPlaceholders("{{llm.reply-timeout-ms:300000}}"));
        try {
            return inFlightReplyManager.await(jobId, timeoutMs);
        } catch (Exception failure) {
            throw new LlmAvailabilityException("LLM reply failure: " + contextSummary(exchange)
                    + " reason=" + failure.getMessage(), failure);
        }
    }

    public LlmJobDeadLetter failedQueuedJobDeadLetter(Exchange exchange) {
        return deadLetters().failedQueuedJobDeadLetter(exchange, globalModelFallback(exchange));
    }

    public void restoreFrameworkMetadata(Exchange exchange) {
        jobs().restoreFrameworkMetadata(exchange);
    }

    @SuppressWarnings("unused")
    private void restoreFrameworkMetadata(Exchange exchange, LlmJobEnvelope job) {
        jobs().restoreFrameworkMetadata(exchange, job);
    }

    public String partitionKey(LlmJobRequest request, Exchange exchange, InferenceLaneDefinition lane) {
        return jobs().partitionKey(request, exchange, lane);
    }

    public String requestKafkaKey(Exchange exchange) {
        return jobs().requestKafkaKey(exchange);
    }

    public UUID jobIdKafkaKey(Exchange exchange) {
        return jobs().jobIdKafkaKey(exchange);
    }

    public boolean shouldApplyProviderSchema(Exchange exchange) {
        return shouldApplyProviderSchema(
                exchange,
                exchange.getProperty("llmJob", LlmJobEnvelope.class),
                exchange.getProperty("executionRuntime", ExecutionRuntimeDefinition.class));
    }

    private boolean shouldApplyProviderSchema(Exchange exchange, LlmJobEnvelope job, ExecutionRuntimeDefinition runtime) {
        if (runtime.toolCalling() || job == null || job.responseSchema() == null || job.responseSchema().isBlank()) {
            return false;
        }
        if (Boolean.TRUE.equals(exchange.getProperty("llmForceProviderSchema", Boolean.class))) {
            return true;
        }
        return Boolean.parseBoolean(exchange.getContext().resolvePropertyPlaceholders("{{llm.structured-output.provider-schema-enabled:false}}"));
    }

    public void verifySelectedLlmRoutesAvailable(Exchange exchange) {
        InferenceLaneDefinition lane = exchange.getProperty("inferenceLane", InferenceLaneDefinition.class);
        if (lane == null) {
            throw new LlmAvailabilityException("Selected LLM inference lane is unavailable: " + contextSummary(exchange)
                    + " reason=missing inferenceLane exchange property");
        }
        String routeSuffix = lane.id().replaceAll("[^A-Za-z0-9_-]", "-");
        if (localWorkerRouteVerificationEnabled(exchange)) {
            verifyRouteStarted(exchange, "llm-worker-" + routeSuffix);
        }
        verifyRouteStarted(exchange, "llm-response-consumer-" + routeSuffix);
    }

    private void verifyRouteStarted(Exchange exchange, String routeId) {
        ServiceStatus status = camelContext.getRouteController().getRouteStatus(routeId);
        if (status == null || !status.isStarted()) {
            throw new LlmAvailabilityException("LLM route '" + routeId + "' is not running. Refusing to queue request: "
                    + contextSummary(exchange) + " reason=route status is " + status);
        }
    }

    public String contextSummary(Exchange exchange) {
        return deadLetters().contextSummary(exchange, globalModelFallback(exchange));
    }

    private String modelId(Exchange exchange) {
        return deadLetters().modelId(exchange, globalModelFallback(exchange));
    }

    private boolean localWorkerRouteVerificationEnabled(Exchange exchange) {
        return Boolean.parseBoolean(exchange.getContext().resolvePropertyPlaceholders("{{llm.local-worker-route-verification.enabled:true}}"));
    }

    private static String resolveOptionalPlaceholder(Exchange exchange, String placeholder) {
        try {
            return exchange.getContext().resolvePropertyPlaceholders(placeholder);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String globalModelFallback(Exchange exchange) {
        return resolveOptionalPlaceholder(exchange, "{{llm.openai.model:}}");
    }

    private LlmJobFactory jobs() {
        if (jobFactory == null) {
            jobFactory = new LlmJobFactory();
        }
        return jobFactory;
    }

    private LlmDeadLetterFactory deadLetters() {
        if (deadLetterFactory == null) {
            deadLetterFactory = new LlmDeadLetterFactory();
        }
        return deadLetterFactory;
    }
}
