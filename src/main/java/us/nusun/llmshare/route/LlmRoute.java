package us.nusun.llmshare.route;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.ServiceStatus;
import org.apache.camel.ValidationException;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jsonvalidator.JsonValidationException;
import org.apache.camel.model.dataformat.JsonLibrary;
import us.nusun.llmshare.bean.LogTokens;
import us.nusun.llmshare.model.LlmJobEnvelope;
import us.nusun.llmshare.model.LlmJobRequest;
import us.nusun.llmshare.model.LlmJobResult;

import java.time.Instant;
import java.util.UUID;

@ApplicationScoped
public class LlmRoute extends RouteBuilder {
    private static final String DEFAULT_SYSTEM_PROMPT = "You are a precise worker in a shared LLM queue. Return only what the requester asked for.";

    public static final class LlmAvailabilityException extends RuntimeException {
        public LlmAvailabilityException(String message) {
            super(message);
        }
    }

    @Inject
    CamelContext camelContext;

    @Inject
    LogTokens logTokens;

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

        from("direct:ask-llm")
                .routeId("ask-llm")
                .to("direct:prepare-llm-job")
                .bean("inFlightReplyManager", "register")
                .to("direct:log-llm-prompt")
                .to("direct:dispatch-llm-job")
                .bean("inFlightReplyManager", "await(${exchangeProperty.llmJobId}, {{llm.reply-timeout-ms:300000}})")
                .to("direct:validate-llm-response-schema")
                .validate(body().isInstanceOf(LlmJobResult.class))
                .process(exchange -> {
                    LlmJobResult result = exchange.getIn().getBody(LlmJobResult.class);
                    logTokens.print(logTokens.wrapDim(
                            exchange.getProperty("agentLabel", String.class),
                            "META " + result.jobId() + " " + result.model() + " " + result.latencyMs() + "ms"));
                })
                .to("direct:log-llm-response");

        from("direct:prepare-llm-job")
                .routeId("prepare-llm-job")
                .process(exchange -> {
                    Object body = exchange.getIn().getBody();
                    UUID overrideJobId = exchange.getProperty("llmJobId", UUID.class);
                    UUID headerJobId = readJobId(exchange.getIn().getHeader("jobId", String.class));
                    UUID jobId = overrideJobId != null ? overrideJobId : headerJobId;

                    LlmJobRequest request = body instanceof LlmJobRequest existing
                            ? normalize(existing, jobId)
                            : LlmJobRequest.fromPrompt(
                                    jobId,
                                    exchange.getIn().getHeader("agent", String.class),
                                    exchange.getIn().getHeader("role", String.class),
                                    body == null ? null : body.toString(),
                                    exchange.getIn().getHeader("systemPrompt", String.class),
                                    exchange.getIn().getHeader("responseSchema", String.class))
                                    .normalized()
                                    .ensureJsonOnlyResponse();

                    exchange.setProperty("agentLabel", displayLabel(exchange, request));
                    exchange.setProperty("llmJobId", request.jobId());
                    exchange.setProperty("llmJob", LlmJobEnvelope.queued(request));
                    exchange.getIn().setBody(exchange.getProperty("llmJob", LlmJobEnvelope.class));
                });

        from("direct:dispatch-llm-job")
                .routeId("dispatch-llm-job")
                .choice()
                    .when(simple("{{llm.mock.enabled:false}} == true"))
                        .to("direct:mock-llm-worker")
                    .otherwise()
                        .process(exchange -> verifyLlmRoutesAvailable())
                        .marshal().json(JsonLibrary.Jackson)
                        .toD("kafka:{{llm.topic.requests}}?key=${exchangeProperty.llmJobId}")
                .end();

        from("direct:log-llm-prompt")
                .routeId("log-llm-prompt")
                .process(exchange -> {
                    LlmJobEnvelope job = exchange.getIn().getBody(LlmJobEnvelope.class);
                    logTokens.print(logTokens.wrapDim(
                            exchange.getProperty("agentLabel", String.class),
                            "PROMPT " + job.prompt()));
                });

        from("direct:log-llm-response")
                .routeId("log-llm-response")
                .process(exchange -> {
                    LlmJobResult result = exchange.getIn().getBody(LlmJobResult.class);
                    logTokens.print(logTokens.wrap(
                            exchange.getProperty("agentLabel", String.class),
                            "RESPONSE " + result.content()));
                });

        from("kafka:{{llm.topic.requests}}"
                + "?groupId={{llm.consumer.group}}"
                + "&consumersCount={{llm.worker.consumers-count}}"
                + "&maxPollRecords={{llm.worker.max-poll-records}}"
                + "&maxPollIntervalMs=900000"
                + "&autoOffsetReset=earliest")
                .routeId("llm-worker")
                .autoStartup("{{llm.worker.enabled}}")
                .unmarshal().json(JsonLibrary.Jackson, LlmJobEnvelope.class)
                .setProperty("llmJob", body())
                .setProperty("agentLabel", simple("${body.requester}"))
                .doTry()
                    .to("direct:call-llm-agent")
                .doCatch(Exception.class)
                    .to("direct:build-failed-llm-result")
                .end()
                .marshal().json(JsonLibrary.Jackson)
                .toD("kafka:{{llm.topic.responses}}?key=${exchangeProperty.llmJob.jobId}");

        from("kafka:{{llm.topic.responses}}"
                + "?groupId={{llm.response.consumer.group}}"
                + "&autoOffsetReset=latest"
                + "&maxPollRecords=1")
                .routeId("llm-response-consumer")
                .unmarshal().json(JsonLibrary.Jackson, LlmJobResult.class)
                .to("direct:handle-llm-response");

        from("direct:handle-llm-response")
                .routeId("handle-llm-response")
                .validate(body().isInstanceOf(LlmJobResult.class))
                .bean("inFlightReplyManager", "complete");

        from("direct:mock-llm-worker")
                .routeId("mock-llm-worker")
                .validate(body().isInstanceOf(LlmJobEnvelope.class))
                .bean("mockLlmResponder", "respond")
                .to("direct:handle-llm-response");

        from("direct:call-llm-agent")
                .routeId("call-llm-agent")
                .setBody(simple("${exchangeProperty.llmJob.prompt}"))
                .setHeader("CamelLangChain4jAgentSystemMessage", simple("${exchangeProperty.llmJob.systemPrompt}"))
                .choice()
                    .when(header("CamelLangChain4jAgentSystemMessage").isNull())
                        .setHeader("CamelLangChain4jAgentSystemMessage", constant(DEFAULT_SYSTEM_PROMPT))
                .end()
                .process(exchange -> exchange.setProperty("llmStartedAt", Instant.now()))
                .choice()
                    .when(simple("${exchangeProperty.llmJob.responseSchema} != null && ${exchangeProperty.llmJob.responseSchema} != '' && {{llm.structured-output.provider-schema-enabled:false}} == true"))
                        .toD("langchain4j-agent:shared-llm?agentConfiguration=#sharedLlmConfig&jsonSchema=${exchangeProperty.llmJob.responseSchema}")
                    .otherwise()
                        .to("langchain4j-agent:shared-llm?agentConfiguration=#sharedLlmConfig")
                .end()
                .validate(body().isNotNull())
                .process(exchange -> {
                    LlmJobEnvelope job = exchange.getProperty("llmJob", LlmJobEnvelope.class);
                    Instant startedAt = exchange.getProperty("llmStartedAt", Instant.class);
                    String model = exchange.getContext().resolvePropertyPlaceholders("{{llm.openai.model}}");
                    String rawResponse = exchange.getIn().getBody(String.class);
                    exchange.getIn().setBody(LlmJobResult.succeeded(job, model, rawResponse, rawResponse, startedAt));
                });

        from("direct:build-failed-llm-result")
                .routeId("build-failed-llm-result")
                .process(exchange -> {
                    Exception failure = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
                    LlmJobEnvelope job = exchange.getProperty("llmJob", LlmJobEnvelope.class);
                    String message = failure == null ? "Unknown LLM worker failure" : failure.getMessage();
                    exchange.getIn().setBody(LlmJobResult.failed(job, "unknown", message, null, Instant.now()));
                });

        from("direct:validate-llm-response-schema")
                .routeId("validate-llm-response-schema")
                .choice()
                    .when(simple("${body.status} == 'SUCCEEDED' && ${body.content} != null && ${body.content} != '' && ${body.requester} != null && ${exchangeProperty.llmJob.responseSchema} != null && ${exchangeProperty.llmJob.responseSchema} != ''"))
                        .setProperty("llmResult", body())
                        .setBody(simple("${body.content}"))
                        .toD("json-validator:${exchangeProperty.llmJob.responseSchema}")
                        .setBody(exchangeProperty("llmResult"))
                .end();
    }

    private static LlmJobRequest normalize(LlmJobRequest request, UUID overrideJobId) {
        LlmJobRequest normalized = overrideJobId == null ? request : request.withJobId(overrideJobId);
        return normalized.normalized().ensureJsonOnlyResponse();
    }

    private static UUID readJobId(String rawJobId) {
        if (rawJobId == null || rawJobId.isBlank()) {
            return null;
        }
        return UUID.fromString(rawJobId);
    }

    private static String displayLabel(Exchange exchange, LlmJobRequest request) {
        String headerLabel = exchange.getIn().getHeader("agentLabel", String.class);
        if (headerLabel != null && !headerLabel.isBlank()) {
            return headerLabel;
        }
        return request.requester();
    }

    private void verifyLlmRoutesAvailable() {
        verifyRouteStarted("llm-worker");
        verifyRouteStarted("llm-response-consumer");
    }

    private void verifyRouteStarted(String routeId) {
        ServiceStatus status = camelContext.getRouteController().getRouteStatus(routeId);
        if (status == null || !status.isStarted()) {
            throw new LlmAvailabilityException("LLM route '" + routeId + "' is not running. Refusing to queue a request that would just hang.");
        }
    }
}
