package us.nusun.llmshare.framework.route;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.LoggingLevel;
import org.apache.camel.ValidationException;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jackson.JacksonDataFormat;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.component.jsonvalidator.JsonValidationException;
import us.nusun.llmshare.framework.llm.LlmDeadLetterFactory;
import us.nusun.llmshare.framework.llm.LlmJobFactory;
import us.nusun.llmshare.framework.llm.LlmRouteSupport;
import us.nusun.llmshare.framework.inventory.InferenceLaneRegistry;
import us.nusun.llmshare.framework.model.InferenceLaneDefinition;
import us.nusun.llmshare.framework.model.LlmJobDeadLetter;
import us.nusun.llmshare.framework.model.LlmJob;
import us.nusun.llmshare.framework.model.LlmJobResult;
import us.nusun.llmshare.framework.tack.PrimitiveExecutor;

@ApplicationScoped
public class LlmRoute extends RouteBuilder {
    @Inject InferenceLaneRegistry lanes;
    @Inject LlmJobFactory jobFactory;
    @Inject LlmDeadLetterFactory deadLetterFactory;
    @Inject LlmRouteSupport routeSupport;
    @Inject PrimitiveExecutor primitiveExecutor;

    @Override
    public void configure() {
        onException(JsonValidationException.class, ValidationException.class)
                .handled(false)
                .logStackTrace(false)
                .logExhausted(false)
                .log(LoggingLevel.WARN, "LLM contract validation failed: ${exception.message}");

        onException(LlmRouteSupport.LlmAvailabilityException.class)
                .handled(false)
                .logStackTrace(false)
                .logExhausted(false)
                .log(LoggingLevel.WARN, "${exception.message}");

        onCompletion()
                .onWhen(exchangeProperty("llmQueueFailureHandling").isEqualTo(true))
                .onFailureOnly()
                .setProperty("llmFailureMessage", simple("${exception.message}"))
                .setProperty("llmFailureContext", method(deadLetterFactory, "contextSummary"))
                .log(LoggingLevel.WARN, "LLM queue failure: ${exchangeProperty.llmFailureContext} reason=${exchangeProperty.llmFailureMessage}")
                .bean("inFlightReplyManager", "discard(${exchangeProperty.llmJobId})")
                .choice()
                    .when(exchangeProperty("llmJob").isNotNull())
                        .wireTap("direct:publish-failed-llm-job-dlq")
                .end();

        from("ai-tool:" + LlmRouteSupport.ACT_TERMINAL_TOOL
                + "?tags=" + LlmRouteSupport.ACT_TOOL_TAG
                + "&description=Complete%20the%20current%20act%20with%20its%20result%20and%20durable%20thought"
                + "&parameter.summary=string"
                + "&parameter.summary.required=true"
                + "&parameter.summary.description=Compact%20externally%20useful%20result%20of%20the%20act"
                + "&parameter.content=string"
                + "&parameter.content.required=true"
                + "&parameter.content.description=Complete%20replacement%20content%20for%20the%20current%20durable%20thought")
                .routeId("finish-act-tool")
                .bean(primitiveExecutor, "finishAct");

        from("direct:llm")
                .routeId("llm")
                .setProperty("llmQueueFailureHandling", constant(true))
                .bean(jobFactory, "prepare")
                .bean("inFlightReplyManager", "register")
                .bean(routeSupport, "logPrompt")
                .bean(routeSupport, "verifySelectedRoutesAvailable")
                .marshal(llmJson(LlmJob.class))
                .setHeader(KafkaConstants.KEY, method(jobFactory, "requestKafkaKey"))
                .recipientList(simple("kafka:${exchangeProperty.laneRequestTopic}"))
                .bean(routeSupport, "awaitReply")
                .bean(routeSupport, "logModelResponse")
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

        for (InferenceLaneDefinition lane : lanes.allLanes()) {
            llmWorker(lane);
        }

        from("direct:handle-llm-response")
                .routeId("handle-llm-response")
                .validate(body().isInstanceOf(LlmJobResult.class))
                .bean("inFlightReplyManager", "complete");

        from("direct:publish-failed-llm-job-dlq")
                .routeId("publish-failed-llm-job-dlq")
                .setBody(method(deadLetterFactory, "failedJobDeadLetter"))
                .log(LoggingLevel.WARN, "Publishing failed LLM job to DLQ: jobId=${body.jobId} lane=${body.laneId} reason=${body.failureMessage}")
                .marshal(llmJson(LlmJobDeadLetter.class))
                .setHeader(KafkaConstants.KEY, method(jobFactory, "jobIdKafkaKey"))
                .toD("kafka:{{llm.topic.dead-letter:llm.dead-letter}}");

        from("direct:call-llm-agent")
                .routeId("call-llm-agent")
                .bean(jobFactory, "prepareWorkerContext")
                .bean(routeSupport, "prepareAgentCall")
                .toD("${exchangeProperty.agentInvocationUri}")
                .log(
                        LoggingLevel.INFO,
                        "LLM agent invocation completed: "
                                + "finishReason=${header.CamelLangChain4jAgentFinishReason} "
                                + "inputTokens=${header.CamelLangChain4jAgentInputTokenCount} "
                                + "outputTokens=${header.CamelLangChain4jAgentOutputTokenCount} "
                                + "totalTokens=${header.CamelLangChain4jAgentTotalTokenCount}"
                )
                .bean(routeSupport, "extractAgentResult")
                .validate(body().isNotNull())
                .setBody(method(routeSupport, "succeededResult"));

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
                + "&autoOffsetReset=earliest")
                .routeId("llm-worker-" + routeSuffix)
                .autoStartup("{{llm.worker.enabled:true}}")
                .unmarshal(llmJson(LlmJob.class))
                .setProperty("llmJob", body())
                .bean(jobFactory, "prepareWorkerContext")
                .doTry()
                    .to("direct:call-llm-agent")
                    .to(publishReplyUri)
                .doCatch(Exception.class)
                    .setProperty("llmFailureMessage", simple("${exception.message}"))
                    .wireTap("direct:publish-failed-llm-job-dlq")
                    .setBody(method(deadLetterFactory, "failedLlmResult"))
                    .to(publishReplyUri)
                .end();

        from(publishReplyUri)
                .routeId("publish-llm-result-" + routeSuffix)
                .marshal(llmJson(LlmJobResult.class))
                .setHeader(KafkaConstants.KEY, method(jobFactory, "jobIdKafkaKey"))
                .to("kafka:" + replyTopic);

        from("kafka:" + replyTopic
                + "?groupId={{llm.response.consumer.group}}-" + laneId
                + "&autoOffsetReset=latest"
                + "&maxPollRecords=1")
                .routeId("llm-response-consumer-" + routeSuffix)
                .unmarshal(llmJson(LlmJobResult.class))
                .to("direct:handle-llm-response");
    }

    private static JacksonDataFormat llmJson(Class<?> type) {
        JacksonDataFormat dataFormat = new JacksonDataFormat(type);
        dataFormat.setModuleClassNames("com.fasterxml.jackson.datatype.jsr310.JavaTimeModule");
        return dataFormat;
    }

}
