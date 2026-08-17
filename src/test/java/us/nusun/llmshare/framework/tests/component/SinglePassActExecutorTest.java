package us.nusun.llmshare.framework.tests.component;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.model.LlmJobEnvelope;
import us.nusun.llmshare.framework.model.LlmJobRequest;
import us.nusun.llmshare.framework.model.LlmJobResult;
import us.nusun.llmshare.framework.model.LlmPassType;
import us.nusun.llmshare.framework.model.Mission;
import us.nusun.llmshare.framework.model.StepResult;
import us.nusun.llmshare.framework.model.ThoughtFrame;
import us.nusun.llmshare.framework.projection.ProjectionStore;
import us.nusun.llmshare.framework.route.TerminalToolRoute;
import us.nusun.llmshare.framework.routine.ThoughtStackSupport;
import us.nusun.llmshare.framework.state.AgentRun;
import us.nusun.llmshare.framework.state.ThoughtStack;
import us.nusun.llmshare.framework.tack.ActExecutor;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;
import us.nusun.llmshare.framework.tests.support.TestCamelContexts;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SinglePassActExecutorTest {
    @Test
    void actQueuesOneToolCapableInvocationAndAppliesTerminalResult() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        ActExecutor executor = FrameworkFixtures.actExecutor(store);
        List<LlmJobRequest> requests = new ArrayList<>();

        try (DefaultCamelContext context = TestCamelContexts.quiet()) {
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:llm").process(exchange -> {
                        LlmJobRequest request = exchange.getMessage().getBody(LlmJobRequest.class);
                        requests.add(request);
                        LlmJobEnvelope job = LlmJobEnvelope.queued(request.normalized());
                        String content = "{\"summary\":\"verified repository state\",\"content\":\"durable repository state\"}";
                        exchange.getMessage().setBody(LlmJobResult.succeeded(job, "test-model", content, content, Instant.now()));
                    });
                }
            });
            context.start();

            Exchange seeded = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
            Exchange exchange = context.getEndpoint("direct:llm").createExchange();
            exchange.setProperty("mission", seeded.getProperty("mission", Mission.class));
            exchange.setProperty("agentRun", seeded.getProperty("agentRun", AgentRun.class));
            ThoughtStack stack = new ThoughtStack();
            stack.push(new ThoughtFrame("root-id", "root", "Track current repository state", 200, "prior repository state"));
            exchange.setProperty(ThoughtStackSupport.STACK_PROPERTY, stack);

            executor.execute(exchange, new ActExecutor.Request(
                    "inspect-repository",
                    "Inspect the repository.",
                    FrameworkFixtures.PROFILE_GAMMA,
                    "high-pass"), context.createProducerTemplate());

            assertEquals(1, requests.size());
            LlmJobRequest request = requests.getFirst();
            assertEquals(LlmPassType.TOOL, request.passType());
            assertEquals("high-pass", request.laneId());
            assertEquals(FrameworkFixtures.PROFILE_GAMMA, request.metadata().get("toolProfile"));
            assertEquals(TerminalToolRoute.TAG, request.metadata().get("camelToolTags"));
            assertTrue(request.systemPrompt().contains("finish_act"));
            assertTrue(request.prompt().contains("Inspect the repository."));
            assertEquals("durable repository state", stack.leaf().content());
            StepResult result = assertInstanceOf(StepResult.class, exchange.getMessage().getBody());
            assertEquals("verified repository state", result.summary());
        }
    }
}
