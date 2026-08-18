package us.nusun.llmshare.framework.tests.component;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.apache.camel.Exchange;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.llm.InFlightReplyManager;
import us.nusun.llmshare.framework.llm.LlmAgentFactory;
import us.nusun.llmshare.framework.llm.LlmDeadLetterFactory;
import us.nusun.llmshare.framework.llm.LlmJobFactory;
import us.nusun.llmshare.framework.llm.LlmRouteSupport;
import us.nusun.llmshare.framework.mcp.McpClientFactory;
import us.nusun.llmshare.framework.memory.ApproximateTokenCountEstimator;
import us.nusun.llmshare.framework.model.LlmJob;
import us.nusun.llmshare.framework.model.Mission;
import us.nusun.llmshare.framework.model.StepResult;
import us.nusun.llmshare.framework.route.LlmRoute;
import us.nusun.llmshare.framework.routine.ThoughtStackSupport;
import us.nusun.llmshare.framework.state.AgentRun;
import us.nusun.llmshare.framework.state.ThoughtStack;
import us.nusun.llmshare.framework.tack.PrimitiveExecutor;
import us.nusun.llmshare.framework.tack.TackComponent;
import us.nusun.llmshare.framework.tack.TackHeaders;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;
import us.nusun.llmshare.framework.tests.support.StubKafkaComponent;
import us.nusun.llmshare.framework.tests.support.TestCamelContexts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SinglePassActAiToolIntegrationTest {
    @Test
    void actDiscoversAndExecutesCamelAiToolsAndStopsImmediatelyAtFinishAct() throws Exception {
        ScriptedChatModel model = new ScriptedChatModel();
        AtomicInteger applicationToolCalls = new AtomicInteger();
        AtomicInteger finishToolCalls = new AtomicInteger();
        StubKafkaComponent[] kafkaRef = new StubKafkaComponent[1];
        StubKafkaComponent kafka = new StubKafkaComponent((topic, exchange) -> {
            if ("llm.requests.low".equals(topic) || "llm.replies.low".equals(topic)) {
                kafkaRef[0].emit(topic, exchange.getIn().getBody());
            }
        });
        kafkaRef[0] = kafka;

        try (DefaultCamelContext context = context(model, kafka, applicationToolCalls)) {
            AdviceWith.adviceWith(context, "finish-act-tool",
                    advice -> advice.weaveAddLast().process(exchange -> finishToolCalls.incrementAndGet()));
            context.start();

            Exchange result = context.createProducerTemplate().request("direct:single-pass-act", this::seedRun);

            StepResult step = assertInstanceOf(StepResult.class, result.getMessage().getBody());
            ThoughtStack stack = result.getProperty(ThoughtStackSupport.STACK_PROPERTY, ThoughtStack.class);
            assertEquals("application state verified", step.summary());
            assertEquals("durable terminal content", stack.leaf().content());
            assertEquals(1, applicationToolCalls.get());
            assertEquals(1, finishToolCalls.get());
            assertEquals(2, model.requests.size(), "one application-tool round trip and no model call after finish_act");
            assertEquals(1, kafka.sentTo("llm.requests.low").size(), "act must enqueue exactly one LLM job");
            assertTrue(model.requests.getFirst().toolSpecifications().stream()
                    .anyMatch(tool -> "inspect_application".equals(tool.name())));
            assertTrue(model.requests.getFirst().toolSpecifications().stream()
                    .anyMatch(tool -> "finish_act".equals(tool.name())));
            ToolExecutionResultMessage observation = model.requests.get(1).messages().stream()
                    .filter(ToolExecutionResultMessage.class::isInstance)
                    .map(ToolExecutionResultMessage.class::cast)
                    .filter(message -> "inspect_application".equals(message.toolName()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("observed application state", observation.text());
            assertEquals(1, model.finishRequests);
        }
    }

    private static DefaultCamelContext context(
            ChatModel model,
            StubKafkaComponent kafka,
            AtomicInteger applicationToolCalls) throws Exception {
        DefaultCamelContext context = TestCamelContexts.quiet();
        context.setAutowiredEnabled(false);
        context.getShutdownStrategy().setTimeout(1);
        Properties properties = new Properties();
        properties.setProperty("llm.consumer.group", "single-pass-act-test");
        properties.setProperty("llm.response.consumer.group", "single-pass-act-test-response");
        properties.setProperty("llm.model-default.openai.model", "test-model");
        properties.setProperty("llm.reply-timeout-ms", "1000");
        properties.setProperty("llm.topic.dead-letter", "llm.dead-letter");
        context.getPropertiesComponent().setOverrideProperties(properties);
        context.addComponent("kafka", kafka);

        InFlightReplyManager replies = new InFlightReplyManager();
        PrimitiveExecutor primitives = FrameworkFixtures.primitiveExecutor(FrameworkFixtures.projectionStore());
        context.getRegistry().bind("inFlightReplyManager", replies);
        FrameworkFixtures.bindCatalogBeans(context);
        LlmAgentFactory.DynamicAgentFactory agents = new LlmAgentFactory.DynamicAgentFactory(
                FrameworkFixtures.inferenceLaneRegistry(),
                Map.of("low-pass", model),
                new McpClientFactory(),
                FrameworkFixtures.toolProfileRegistry(),
                FrameworkFixtures.personaCatalog(),
                new ApproximateTokenCountEstimator(),
                12000);
        agents.setCamelContext(context);
        context.getRegistry().bind("dynamicAgentFactory", agents);
        context.addComponent("tack", new TackComponent(primitives));

        LlmDeadLetterFactory deadLetters = new LlmDeadLetterFactory();
        LlmRoute llmRoute = new LlmRoute();
        FrameworkFixtures.set(llmRoute, "lanes", FrameworkFixtures.inferenceLaneRegistry());
        FrameworkFixtures.set(llmRoute, "jobFactory", new LlmJobFactory(
                FrameworkFixtures.inferenceLaneRegistry(), FrameworkFixtures.personaCatalog()));
        FrameworkFixtures.set(llmRoute, "deadLetterFactory", deadLetters);
        FrameworkFixtures.set(llmRoute, "routeSupport", new LlmRouteSupport(
                context, FrameworkFixtures.inferenceLaneRegistry(), replies, deadLetters));
        FrameworkFixtures.set(llmRoute, "primitiveExecutor", primitives);
        context.addRoutes(llmRoute);
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("ai-tool:inspect_application"
                        + "?tags=" + LlmRouteSupport.ACT_TOOL_TAG
                        + "&description=Inspect%20application%20state")
                        .routeId("inspect-application-tool")
                        .process(exchange -> {
                            LlmJob job = exchange.getProperty("llmJob", LlmJob.class);
                            assertNotNull(job, "Camel must copy the originating LLM job context to the tool exchange");
                            assertEquals("act", job.metadata().get("primitive"));
                            applicationToolCalls.incrementAndGet();
                            exchange.getMessage().setBody("observed application state");
                        });
                from("direct:single-pass-act")
                        .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Initial durable thought"))
                        .to("tack:thought-enter?name=current&maxTokens=200")
                        .setHeader(TackHeaders.PROMPT, constant("Inspect the application, then finish the act."))
                        .to("tack:act?id=inspect&toolProfile=none");
            }
        });
        return context;
    }

    private void seedRun(Exchange exchange) {
        Exchange seeded = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        exchange.setProperty("mission", seeded.getProperty("mission", Mission.class));
        exchange.setProperty("agentRun", seeded.getProperty("agentRun", AgentRun.class));
    }

    private static final class ScriptedChatModel implements ChatModel {
        private final List<ChatRequest> requests = new ArrayList<>();
        private int finishRequests;

        @Override
        public ChatResponse doChat(ChatRequest request) {
            requests.add(request);
            if (requests.size() == 1) {
                return toolRequest("inspect-call", "inspect_application", "{}");
            }
            if (requests.size() == 2) {
                finishRequests++;
                return toolRequest(
                        "finish-call",
                        "finish_act",
                        "{\"summary\":\"application state verified\",\"content\":\"durable terminal content\"}");
            }
            throw new AssertionError("finish_act must return immediately without another model round trip");
        }

        private static ChatResponse toolRequest(String id, String name, String arguments) {
            return ChatResponse.builder()
                    .aiMessage(new AiMessage(List.of(ToolExecutionRequest.builder()
                            .id(id)
                            .name(name)
                            .arguments(arguments)
                            .build())))
                    .build();
        }
    }
}
