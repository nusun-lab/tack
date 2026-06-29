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
import org.apache.camel.component.jsonvalidator.JsonValidationException;
import org.apache.camel.component.langchain4j.agent.api.AiAgentBody;
import us.nusun.llmshare.framework.inventory.PersonaCatalog;
import us.nusun.llmshare.framework.llm.InFlightReplyManager;
import us.nusun.llmshare.framework.model.LlmJobDeadLetter;
import us.nusun.llmshare.framework.model.LlmJobEnvelope;
import us.nusun.llmshare.framework.model.LlmJobRequest;
import us.nusun.llmshare.framework.model.LlmJobResult;
import us.nusun.llmshare.framework.model.Persona;
import us.nusun.llmshare.framework.model.ExecutionRuntimeDefinition;
import us.nusun.llmshare.framework.inventory.ExecutionRuntimeRegistry;
import us.nusun.llmshare.framework.model.InferenceLaneDefinition;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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
                .log(LoggingLevel.WARN, "LLM queue failure: jobId=${exchangeProperty.llmJobId} laneId=${exchangeProperty.inferenceLane.id} runtimeId=${exchangeProperty.executionRuntimeId} agentName=${exchangeProperty.executionRuntime.agentName} model={{llm.openai.model:unknown}} reason=${exchangeProperty.llmFailureMessage}")
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
                .setProperty("laneRequestKafkaUri", simple("kafka:${exchangeProperty.laneRequestTopic}?key=${exchangeProperty.llmJobId}"))
                .recipientList(exchangeProperty("laneRequestKafkaUri"))
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
                .toD("kafka:{{llm.topic.dead-letter:llm.dead-letter}}?key=${exchangeProperty.llmJobId}");

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
        from("kafka:" + requestTopic
                + "?groupId={{llm.consumer.group}}-" + laneId
                + "&consumersCount=1"
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
                .toD("kafka:" + replyTopic + "?key=${exchangeProperty.llmJob.jobId}");

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
        Object body = exchange.getIn().getBody();
        UUID overrideJobId = exchange.getProperty("llmJobId", UUID.class);
        UUID headerJobId = readJobId(exchange.getIn().getHeader("jobId", String.class));
        UUID jobId = overrideJobId != null ? overrideJobId : headerJobId;
        String runtimeId = firstNonBlank(
                exchange.getIn().getHeader("executionRuntime", String.class),
                exchange.getProperty("executionRuntimeId", String.class),
                exchange.getContext().resolvePropertyPlaceholders("{{agent.framework.default-synthesis-runtime:synthesis-low}}"));

        LlmJobRequest request = body instanceof LlmJobRequest existing
                ? normalize(existing, jobId, runtimeId)
                : LlmJobRequest.fromPrompt(
                        jobId,
                        runtimeId,
                        exchange.getIn().getHeader("role", String.class),
                        body == null ? null : body.toString(),
                        exchange.getIn().getHeader("systemPrompt", String.class),
                        exchange.getIn().getHeader("responseSchema", String.class))
                        .normalized()
                        .ensureJsonOnlyResponse();

        ExecutionRuntimeDefinition runtime = runtimes.getRequired(request.requester());
        InferenceLaneDefinition lane = runtimes.laneRequired(runtime.laneId());
        request = withRouteMetadata(request, exchange, runtime, lane);
        exchange.setProperty("executionRuntime", runtime);
        exchange.setProperty("executionRuntimeId", runtime.id());
        exchange.setProperty("inferenceLane", lane);
        exchange.setProperty("laneRequestTopic", lane.requestTopic());
        exchange.setProperty("laneReplyTopic", lane.responseTopic());
        applyLogPersona(exchange, runtime.id());
        exchange.setProperty("llmJobId", request.jobId());
        exchange.setProperty("llmJob", LlmJobEnvelope.queued(request));
        exchange.getIn().setBody(exchange.getProperty("llmJob", LlmJobEnvelope.class));
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
        String model = exchange.getContext().resolvePropertyPlaceholders("{{llm.openai.model}}");
        String rawResponse = exchange.getIn().getBody(String.class);
        return LlmJobResult.succeeded(job, model, rawResponse, rawResponse, startedAt);
    }

    public LlmJobResult failedLlmResult(Exchange exchange) {
        Exception failure = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
        LlmJobEnvelope job = exchange.getProperty("llmJob", LlmJobEnvelope.class);
        String message = failure == null ? "Unknown LLM worker failure" : failure.getMessage();
        return LlmJobResult.failed(job, "unknown", message, null, Instant.now());
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
        LlmJobEnvelope job = exchange.getProperty("llmJob", LlmJobEnvelope.class);
        if (job == null) {
            throw new IllegalStateException("Cannot publish LLM DLQ entry without llmJob exchange property");
        }
        exchange.setProperty("llmJobId", job.jobId());
        return new LlmJobDeadLetter(
                job.jobId(),
                firstNonBlank(exchange.getProperty("inferenceLane", String.class), metadataValue(job, "inferenceLane")),
                firstNonBlank(exchange.getProperty("executionRuntimeId", String.class), metadataValue(job, "executionRuntime"), job.requester()),
                job.requester(),
                job.role(),
                job.metadata() == null ? Map.of() : Map.copyOf(job.metadata()),
                firstNonBlank(exchange.getProperty("llmFailureMessage", String.class), "Unknown LLM queue failure"),
                Instant.now());
    }

    public void restoreFrameworkMetadata(Exchange exchange) {
        restoreFrameworkMetadata(exchange, exchange.getProperty("llmJob", LlmJobEnvelope.class));
    }

    private void applyLogPersona(Exchange exchange, String fallbackLabel) {
        String personaId = exchange.getProperty("personaId", String.class);
        if (personaId == null || personaId.isBlank()) {
            exchange.setProperty("agentLabel", fallbackLabel);
            exchange.removeProperty("agentLogColor");
            return;
        }
        Persona persona = personas.getRequired(personaId);
        exchange.setProperty("agentLabel", firstNonBlank(persona.label(), persona.name(), fallbackLabel));
        exchange.setProperty("agentLogColor", persona.color());
    }

    private LlmJobRequest withRouteMetadata(LlmJobRequest request, Exchange exchange, ExecutionRuntimeDefinition runtime, InferenceLaneDefinition lane) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        if (request.metadata() != null) {
            metadata.putAll(request.metadata());
        }
        putIfPresent(metadata, "executionRuntime", runtime.id());
        putIfPresent(metadata, "inferenceLane", lane.id());
        putIfPresent(metadata, "personaId", exchange.getProperty("personaId", String.class));
        putIfPresent(metadata, "toolProfile", exchange.getProperty("toolProfile", String.class));
        return new LlmJobRequest(request.jobId(), request.requester(), request.role(), request.priority(), request.prompt(), request.systemPrompt(), request.responseSchema(), Map.copyOf(metadata), request.requestedAt());
    }

    private void restoreFrameworkMetadata(Exchange exchange, LlmJobEnvelope job) {
        if (job == null || job.metadata() == null) {
            return;
        }
        job.metadata().forEach((key, value) -> {
            if (value != null) {
                exchange.setProperty(key, String.valueOf(value));
            }
        });
        putMetadataAlias(exchange, job, "executionRuntime", "executionRuntimeId");
        putMetadataAlias(exchange, job, "primitive", "currentPrimitive");
        putMetadataAlias(exchange, job, "primitiveId", "currentPrimitiveId");
    }

    private static void putMetadataAlias(Exchange exchange, LlmJobEnvelope job, String from, String to) {
        Object value = job.metadata().get(from);
        if (value != null) {
            exchange.setProperty(to, String.valueOf(value));
        }
    }

    private static void putIfPresent(Map<String, Object> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }

    private static LlmJobRequest normalize(LlmJobRequest request, UUID overrideJobId, String runtimeId) {
        LlmJobRequest normalized = overrideJobId == null ? request : request.withJobId(overrideJobId);
        if (normalized.requester() == null || normalized.requester().isBlank() || "anonymous".equals(normalized.requester())) {
            normalized = new LlmJobRequest(normalized.jobId(), runtimeId, normalized.role(), normalized.priority(), normalized.prompt(), normalized.systemPrompt(), normalized.responseSchema(), normalized.metadata(), normalized.requestedAt());
        }
        return normalized.normalized().ensureJsonOnlyResponse();
    }

    private static UUID readJobId(String rawJobId) {
        if (rawJobId == null || rawJobId.isBlank()) {
            return null;
        }
        return UUID.fromString(rawJobId);
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
        verifyRouteStarted(exchange, "llm-worker-" + routeSuffix);
        verifyRouteStarted(exchange, "llm-response-consumer-" + routeSuffix);
    }

    private void verifyRouteStarted(Exchange exchange, String routeId) {
        ServiceStatus status = camelContext.getRouteController().getRouteStatus(routeId);
        if (status == null || !status.isStarted()) {
            throw new LlmAvailabilityException("LLM route '" + routeId + "' is not running. Refusing to queue request: "
                    + contextSummary(exchange) + " reason=route status is " + status);
        }
    }

    private String contextSummary(Exchange exchange) {
        LlmJobEnvelope job = exchange.getProperty("llmJob", LlmJobEnvelope.class);
        ExecutionRuntimeDefinition runtime = exchange.getProperty("executionRuntime", ExecutionRuntimeDefinition.class);
        InferenceLaneDefinition lane = exchange.getProperty("inferenceLane", InferenceLaneDefinition.class);
        String model = resolveOptionalPlaceholder(exchange, "{{llm.openai.model:}}");
        return "jobId=" + firstNonBlank(exchange.getProperty("llmJobId", String.class), job == null ? null : String.valueOf(job.jobId()))
                + " laneId=" + firstNonBlank(lane == null ? null : lane.id(), metadataValue(job, "inferenceLane"))
                + " runtimeId=" + firstNonBlank(exchange.getProperty("executionRuntimeId", String.class), runtime == null ? null : runtime.id(), job == null ? null : job.requester())
                + " agentName=" + (runtime == null ? null : runtime.agentName())
                + " model=" + firstNonBlank(model, "unknown");
    }

    private static String metadataValue(LlmJobEnvelope job, String key) {
        if (job == null || job.metadata() == null) {
            return null;
        }
        Object value = job.metadata().get(key);
        return value == null ? null : String.valueOf(value);
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
}
