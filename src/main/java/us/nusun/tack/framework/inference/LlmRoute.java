package us.nusun.tack.framework.inference;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.ExchangePropertyKey;
import org.apache.camel.LoggingLevel;
import org.apache.camel.ValidationException;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jackson.JacksonDataFormat;
import org.apache.camel.component.kafka.KafkaConstants;
import us.nusun.tack.framework.inference.lane.InferenceLaneRegistry;
import us.nusun.tack.framework.inference.memory.ToolLoopGuardChatMemory;
import us.nusun.tack.framework.inference.lane.InferenceLaneDefinition;

import java.time.Instant;

@ApplicationScoped
public class LlmRoute extends RouteBuilder {
    @Inject InferenceLaneRegistry lanes;
    @Inject LlmJobFactory jobFactory;
    @Inject LlmDeadLetterFactory deadLetterFactory;
    @Inject LlmRouteSupport routeSupport;

    @Override
    public void configure() {
        onException(ValidationException.class)
                .handled(false)
                .logStackTrace(false)
                .logExhausted(false)
                .log(LoggingLevel.WARN, "LLM contract validation failed: ${exception.message}");

        onException(LlmRouteSupport.LlmAvailabilityException.class)
                .handled(false)
                .logStackTrace(false)
                .logExhausted(false)
                .log(LoggingLevel.WARN, "${exception.message}");

        onException(LlmRouteSupport.LlmResponseException.class)
                .handled(false)
                .logStackTrace(false)
                .logExhausted(false)
                .log(LoggingLevel.WARN, "${exception.message}");

        from("direct:llm")
                .routeId("llm")
                .onCompletion().onFailureOnly()
                    // A direct caller can share the unit of work and fail after inference succeeds.
                    .onWhen(exchangeProperty(ExchangePropertyKey.FAILURE_ROUTE_ID.getName()).isEqualTo("llm"))
                    .setProperty("llmFailureMessage", simple("${exception.message}"))
                    .setProperty("llmFailureContext", method(deadLetterFactory, "contextSummary"))
                    .log(LoggingLevel.WARN, "LLM queue failure: ${exchangeProperty.llmFailureContext} reason=${exchangeProperty.llmFailureMessage}")
                    .filter(exchangeProperty("llmJob").isNotNull())
                        .wireTap("direct:publish-failed-llm-job-dlq")
                        .end()
                    .end()
                .end()
                .bean(jobFactory, "prepare")
                .doTry()
                    .bean(routeSupport, "registerReply")
                    .bean(routeSupport, "verifySelectedRoutesAvailable")
                    .marshal(llmJson(LlmJobEnvelope.class))
                    .setHeader(KafkaConstants.KEY, method(jobFactory, "requestKafkaKey"))
                    .toD("kafka:${exchangeProperty.laneRequestTopic}?synchronous=true")
                    .bean(routeSupport, "awaitReply")
                .doFinally()
                    .bean(routeSupport, "discardReply")
                .end()
                .bean(routeSupport, "logModelResponse")
                .bean(routeSupport, "validateResponseSchema")
                .validate(body().isInstanceOf(LlmJobResult.class));

        for (InferenceLaneDefinition lane : lanes.allLanes()) {
            llmWorker(lane);
        }

        from("direct:publish-failed-llm-job-dlq")
                .routeId("publish-failed-llm-job-dlq")
                .setBody(method(deadLetterFactory, "failedQueuedJobDeadLetter"))
                .log(LoggingLevel.WARN, "Publishing failed LLM job to DLQ: jobId=${body.jobId} lane=${body.laneId} passType=${body.passType} reason=${body.failureMessage}")
                .setHeader(KafkaConstants.KEY, simple("${body.jobId}"))
                .marshal(llmJson(LlmJobDeadLetter.class))
                .to("kafka:{{llm.topic.dead-letter:llm.dead-letter}}");

        from("direct:call-llm-agent")
                .routeId("call-llm-agent")
                .bean(jobFactory, "restore")
                .setProperty("llmStartedAt", Instant::now)
                .setBody(method(routeSupport, "agentBody"))
                .to("langchain4j-agent:dynamic?agentFactory=#dynamicAgentFactory")
                .log(
                        LoggingLevel.INFO,
                        "LLM agent pass completed: "
                                + "finishReason=${header.CamelLangChain4jAgentFinishReason} "
                                + "inputTokens=${header.CamelLangChain4jAgentInputTokenCount} "
                                + "outputTokens=${header.CamelLangChain4jAgentOutputTokenCount} "
                                + "totalTokens=${header.CamelLangChain4jAgentTotalTokenCount}"
                )
                .bean(routeSupport, "requireAgentResponse")
                .setBody(method(routeSupport, "succeededResult"));

        from("direct:execute-claimed-llm-job")
                .routeId("execute-claimed-llm-job")
                .doTry()
                    .to("direct:call-llm-agent")
                .doCatch(ToolLoopGuardChatMemory.ToolPassTerminatedException.class)
                    .log(LoggingLevel.WARN, "${exception.message}")
                    .setBody(simple("${exception.message}"))
                    .setBody(method(routeSupport, "succeededResult"))
                .doCatch(Exception.class)
                    .setProperty("llmFailureMessage", simple("${exception.message}"))
                    .setBody(method(deadLetterFactory, "failedLlmResult"))
                .doFinally()
                    .bean(routeSupport, "completeWorkerJob")
                .end()
                .filter(simple("${body.status} == 'FAILED'"))
                    .wireTap("direct:publish-failed-llm-job-dlq")
                .end();
    }

    private void llmWorker(InferenceLaneDefinition lane) {
        String laneId = lane.id();
        String requestTopic = lane.requestTopic();
        String replyTopic = lane.responseTopic();
        String routeSuffix = LlmRouteSupport.routeSuffix(laneId);
        String groupSuffix = routeSuffix.isBlank() ? "lane" : routeSuffix;
        String publishReplyUri = "direct:publish-llm-result-" + routeSuffix;
        from("kafka:" + requestTopic
                + "?groupId={{llm.consumer.group:llm-workers}}." + groupSuffix
                + "&consumersCount=" + lane.parallelism()
                + "&maxPollRecords=1"
                + "&maxPollIntervalMs=900000"
                + "&autoCommitEnable=false&allowManualCommit=true"
                + "&autoOffsetReset=earliest")
                .routeId("llm-worker-" + routeSuffix)
                .autoStartup("{{llm.worker.enabled:true}}")
                .unmarshal(llmJson(LlmJobEnvelope.class))
                .setProperty("llmJob", body())
                .bean(routeSupport, "commitWorkerRequest")
                .filter(method(routeSupport, "claimWorkerJob"))
                    .to("direct:execute-claimed-llm-job")
                    .to(publishReplyUri)
                .end();

        from(publishReplyUri)
                .routeId("publish-llm-result-" + routeSuffix)
                .setHeader(KafkaConstants.KEY, simple("${body.jobId}"))
                .marshal(llmJson(LlmJobResult.class))
                .to("kafka:" + replyTopic);
    }

    private static JacksonDataFormat llmJson(Class<?> type) {
        JacksonDataFormat dataFormat = new JacksonDataFormat(type);
        dataFormat.setModuleClassNames("com.fasterxml.jackson.datatype.jsr310.JavaTimeModule");
        return dataFormat;
    }

}
