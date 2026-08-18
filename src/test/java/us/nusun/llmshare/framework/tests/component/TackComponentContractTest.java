package us.nusun.llmshare.framework.tests.component;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.model.AgentRunProjection;
import us.nusun.llmshare.framework.model.LlmJobEnvelope;
import us.nusun.llmshare.framework.model.LlmJobRequest;
import us.nusun.llmshare.framework.model.LlmJobResult;
import us.nusun.llmshare.framework.model.LlmJobStatus;
import us.nusun.llmshare.framework.model.Mission;
import us.nusun.llmshare.framework.model.StepResult;
import us.nusun.llmshare.framework.model.ThoughtFrame;
import us.nusun.llmshare.framework.projection.ProjectionStore;
import us.nusun.llmshare.framework.state.AgentRun;
import us.nusun.llmshare.framework.routine.ThoughtStackSupport;
import us.nusun.llmshare.framework.state.ThoughtStack;
import us.nusun.llmshare.framework.tack.TackComponent;
import us.nusun.llmshare.framework.tack.TackHeaders;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;
import us.nusun.llmshare.framework.tests.support.TestCamelContexts;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TackComponentContractTest {
    @Test
    void tackActUsesOneLlmJobAndTerminalResultCompletesBeforeNextRouteStep() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        ScriptedLlm llm = new ScriptedLlm((request, job) -> terminal(
                job,
                "verified evidence for " + request.metadata().get("primitiveId"),
                "leaf memory after " + request.metadata().get("primitiveId")));

        try (DefaultCamelContext context = context(store, llm)) {
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:tack-act-contract")
                            .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Current contract thought."))
                            .to("tack:thought-enter?name=contract&maxTokens=200")
                            .setHeader(TackHeaders.PROMPT, constant("Inspect contract state."))
                            .to("tack:act?id=contract-act&toolProfile=" + FrameworkFixtures.PROFILE_GAMMA)
                            .process(exchange -> exchange.setProperty("afterActContent", exchange.getProperty(ThoughtStackSupport.STACK_PROPERTY, ThoughtStack.class).leaf().content()))
                            .setProperty("promptAfterAct", header(TackHeaders.PROMPT));
                }
            });
            context.start();

            Exchange result = send(context, "direct:tack-act-contract");

            StepResult step = assertInstanceOf(StepResult.class, result.getMessage().getBody());
            assertEquals("verified evidence for contract-act", step.summary());
            assertEquals("leaf memory after contract-act", result.getProperty("afterActContent"));
            assertNull(result.getProperty("promptAfterAct"));
            assertEquals(1, llm.requests.size());
            assertEquals(FrameworkFixtures.PROFILE_GAMMA, llm.requests.getFirst().metadata().get("toolProfile"));
            assertTrue(llm.requests.getFirst().prompt().contains("Inspect contract state."));
            assertTrue(llm.requests.getFirst().prompt().contains("finish_act"));
            assertNull(llm.requests.getFirst().responseSchema());
            assertFalse(llm.requests.getFirst().prompt().contains("t_"));
        }
    }

    @Test
    void validationFailuresAreClearAndDynamicEndpointParametersWork() throws Exception {
        ScriptedLlm llm = new ScriptedLlm((request, job) -> terminal(job, "dynamic evidence", "dynamic memory"));
        try (DefaultCamelContext context = context(FrameworkFixtures.projectionStore(), llm)) {
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    errorHandler(defaultErrorHandler()
                            .maximumRedeliveries(0)
                            .logExhausted(false)
                            .logStackTrace(false));
                    from("direct:dynamic-act")
                            .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Dynamic thought."))
                            .to("tack:thought-enter?name=dynamic&maxTokens=100")
                            .setHeader("actId", constant("dynamic-act"))
                            .setHeader(TackHeaders.PROMPT, constant("Dynamic prompt."))
                            .toD("tack:act?id=${header.actId}&toolProfile=" + FrameworkFixtures.PROFILE_GAMMA);
                    from("direct:missing-id")
                            .setHeader(TackHeaders.PROMPT, constant("Prompt."))
                            .to("tack:act?toolProfile=" + FrameworkFixtures.PROFILE_GAMMA);
                    from("direct:missing-prompt")
                            .to("tack:act?id=missing-prompt&toolProfile=" + FrameworkFixtures.PROFILE_GAMMA);
                }
            });
            context.start();

            Exchange result = send(context, "direct:dynamic-act");
            assertEquals("dynamic memory", result.getProperty(ThoughtStackSupport.STACK_PROPERTY, ThoughtStack.class).leaf().content());

            Exchange missingId = send(context, "direct:missing-id");
            assertTrue(missingId.isFailed());
            assertTrue(missingId.getException().getMessage().contains("Missing required id parameter for tack:act"));

            Exchange missingPrompt = send(context, "direct:missing-prompt");
            assertTrue(missingPrompt.isFailed());
            assertTrue(missingPrompt.getException().getMessage().contains("Missing required TackPrompt header for tack:act"));
        }
    }

    @Test
    void failureBeforeFinishActDoesNotRewriteDurableThought() throws Exception {
        assertActFailurePreservesThought(
                (request, job) -> "ordinary final model text",
                "Act agent did not reach finish_act: invalid terminal result");
    }

    @Test
    void wrongMissingAndBlankTerminalFieldsFailWithoutRewritingDurableThought() throws Exception {
        assertActFailurePreservesThought(
                (request, job) -> "{\"jobId\":\"00000000-0000-0000-0000-000000000000\",\"summary\":\"done\",\"content\":\"after\"}",
                "terminal job id is missing or wrong");
        assertActFailurePreservesThought(
                (request, job) -> "{\"summary\":\"done\",\"content\":\"after\"}",
                "terminal job id is missing or wrong");
        assertActFailurePreservesThought(
                (request, job) -> terminal(job, " ", "after"),
                "finish_act.summary must not be blank");
        assertActFailurePreservesThought(
                (request, job) -> terminal(job, "done", " "),
                "finish_act.content must not be blank");
    }

    @Test
    void decideAndCreateEndpointsReturnTypedResultsFromPromptHeaders() throws Exception {
        ScriptedLlm llm = new ScriptedLlm((request, job) -> {
            if (request.responseSchema() != null && request.responseSchema().contains("decision-result")) {
                return "{\"decisionId\":\"ready\",\"result\":true,\"confidence\":0.87,\"reason\":\"evidence is present\"}";
            }
            return "{\"value\":\"created\"}";
        });
        try (DefaultCamelContext context = context(FrameworkFixtures.projectionStore(), llm)) {
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:tack-decide-create")
                            .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Root."))
                            .to("tack:thought-enter?name=root&maxTokens=100")
                            .setHeader(TackHeaders.PROMPT, constant("Is it ready?"))
                            .to("tack:decide?id=ready")
                            .setHeader(TackHeaders.PROMPT, constant("Create a value."))
                            .to("tack:create?id=create-value&schemaId=value-schema&schema={\"type\":\"object\",\"required\":[\"value\"]}");
                }
            });
            context.start();

            Exchange result = send(context, "direct:tack-decide-create");

            assertEquals(true, result.getProperty("decision.ready.result"));
            assertEquals("{\"value\":\"created\"}", result.getMessage().getBody(String.class));
            assertEquals(2, llm.requests.size());
            assertTrue(llm.requests.get(0).prompt().contains("Is it ready?"));
            assertTrue(llm.requests.get(1).prompt().contains("Create a value."));
        }
    }

    @Test
    void repeatedChildActAndExitRewritesEachChildAndConsolidatesEachTurn() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        ScriptedLlm llm = new ScriptedLlm(new BiFunction<>() {
            int act = 0;
            int consolidation = 0;

            @Override
            public String apply(LlmJobRequest request, LlmJobEnvelope job) {
                if ("act".equals(request.metadata().get("primitive"))) {
                    act++;
                    return terminal(job, "tool evidence turn " + act, "child memory turn " + act);
                }
                consolidation++;
                return "{\"content\":\"root memory after turn " + consolidation + "\"}";
            }
        });

        try (DefaultCamelContext context = context(store, llm)) {
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:loop")
                            .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Root durable memory."))
                            .to("tack:thought-enter?name=root&maxTokens=300")
                            .loop(2)
                                .setHeader(TackHeaders.THOUGHT_PURPOSE, simple("Child turn ${exchangeProperty.CamelLoopIndex}."))
                                .to("tack:thought-enter?name=turn&maxTokens=120")
                                .setHeader(TackHeaders.PROMPT, simple("Act turn ${exchangeProperty.CamelLoopIndex}."))
                                .to("tack:act?id=turn-act&toolProfile=" + FrameworkFixtures.PROFILE_GAMMA)
                                .to("tack:thought-exit?name=turn")
                            .end();
                }
            });
            context.start();

            Exchange result = send(context, "direct:loop");

            ThoughtStack stack = result.getProperty(ThoughtStackSupport.STACK_PROPERTY, ThoughtStack.class);
            assertEquals(1, stack.frames().size());
            assertEquals("root", stack.leaf().name());
            assertEquals("root memory after turn 2", stack.leaf().content());
            assertEquals(4, llm.requests.size());
            assertEquals(2, llm.requests.stream().filter(r -> "act".equals(r.metadata().get("primitive"))).count());
            assertEquals(2, llm.requests.stream().filter(r -> "thought-exit".equals(r.metadata().get("primitive"))).count());
            assertTrue(llm.requests.stream().noneMatch(r -> r.prompt().contains("t_")));

            AgentRunProjection projection = store.get("run-1");
            assertEquals(1, projection.thoughtStack().size());
            assertFalse(projection.thoughtHistory().isEmpty());
            assertTrue(types(projection.thoughtHistory()).contains("THOUGHT_REWRITTEN"));
            assertTrue(types(projection.thoughtHistory()).contains("THOUGHT_CONSOLIDATION_STARTED"));
            assertTrue(types(projection.thoughtHistory()).contains("THOUGHT_CONSOLIDATED"));
            assertTrue(types(projection.thoughtHistory()).contains("THOUGHT_EXITED"));
            assertTrue(projection.thoughtHistory().stream().anyMatch(row -> String.valueOf(row.get("content")).contains("child memory turn 1")));
            assertTrue(projection.thoughtHistory().stream().anyMatch(row -> String.valueOf(row.get("resultingParentContent")).contains("root memory after turn 2")));
        }
    }

    @Test
    void failedConsolidationLeavesParentAndChildIntact() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        ScriptedLlm llm = new ScriptedLlm((request, job) -> "{\"content\":\"unused\"}");
        llm.failConsolidation = true;

        try (DefaultCamelContext context = context(store, llm)) {
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    errorHandler(defaultErrorHandler()
                            .maximumRedeliveries(0)
                            .logExhausted(false)
                            .logStackTrace(false));
                    from("direct:failed-exit")
                            .to("tack:thought-exit?name=child");
                }
            });
            context.start();
            Exchange exchange = seededExchange(context);
            ThoughtStack stack = new ThoughtStack();
            stack.push(new ThoughtFrame("parent-id", "parent", "Parent purpose", 100, "parent before"));
            stack.push(new ThoughtFrame("child-id", "child", "Child purpose", 100, "child final"));
            exchange.setProperty(ThoughtStackSupport.STACK_PROPERTY, stack);

            Exchange failure = context.createProducerTemplate().send("direct:failed-exit", exchange);

            assertTrue(failure.isFailed());
            assertTrue(failure.getException().getMessage().contains("thought consolidation call failed"));
            assertEquals(2, stack.frames().size());
            assertEquals("parent before", stack.frames().get(0).content());
            assertEquals("child final", stack.frames().get(1).content());
            AgentRunProjection projection = store.get("run-1");
            assertEquals(2, projection.thoughtStack().size());
            assertTrue(types(projection.thoughtHistory()).contains("THOUGHT_CONSOLIDATION_STARTED"));
            assertFalse(types(projection.thoughtHistory()).contains("THOUGHT_CONSOLIDATED"));
        }
    }

    @Test
    void rootAndEmptyChildExitDoNotAttemptConsolidation() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        ScriptedLlm llm = new ScriptedLlm((request, job) -> {
            throw new AssertionError("No LLM call expected");
        });
        try (DefaultCamelContext context = context(store, llm)) {
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:root-exit")
                            .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Root."))
                            .to("tack:thought-enter?name=root&maxTokens=100")
                            .to("tack:thought-exit?name=root");
                    from("direct:empty-child-exit")
                            .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Root."))
                            .to("tack:thought-enter?name=root&maxTokens=100")
                            .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("Child."))
                            .to("tack:thought-enter?name=child&maxTokens=100")
                            .to("tack:thought-exit?name=child");
                }
            });
            context.start();

            Exchange root = send(context, "direct:root-exit");
            assertTrue(root.getProperty(ThoughtStackSupport.STACK_PROPERTY, ThoughtStack.class).frames().isEmpty());

            Exchange child = send(context, "direct:empty-child-exit");
            ThoughtStack stack = child.getProperty(ThoughtStackSupport.STACK_PROPERTY, ThoughtStack.class);
            assertEquals(1, stack.frames().size());
            assertEquals("root", stack.leaf().name());
            assertEquals("", stack.leaf().content());
            assertTrue(llm.requests.isEmpty());
        }
    }


    private static void assertActFailurePreservesThought(
            BiFunction<LlmJobRequest, LlmJobEnvelope, String> response,
            String expectedMessage) throws Exception {
        ScriptedLlm llm = new ScriptedLlm(response);
        try (DefaultCamelContext context = context(FrameworkFixtures.projectionStore(), llm)) {
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    errorHandler(defaultErrorHandler()
                            .maximumRedeliveries(0)
                            .logExhausted(false)
                            .logStackTrace(false));
                    from("direct:invalid-act")
                            .to("tack:act?id=invalid&toolProfile=" + FrameworkFixtures.PROFILE_GAMMA);
                }
            });
            context.start();

            Exchange exchange = seededExchange(context);
            ThoughtStack stack = new ThoughtStack();
            stack.push(new ThoughtFrame("leaf-id", "leaf", "Preserve this.", 100, "before"));
            exchange.setProperty(ThoughtStackSupport.STACK_PROPERTY, stack);
            exchange.getMessage().setHeader(TackHeaders.PROMPT, "Try the act.");

            Exchange failure = context.createProducerTemplate().send("direct:invalid-act", exchange);

            assertTrue(failure.isFailed());
            assertTrue(failure.getException().getMessage().contains(expectedMessage));
            assertEquals("before", stack.leaf().content());
            assertEquals(1, llm.requests.size());
        }
    }

    private static String terminal(LlmJobEnvelope job, String summary, String content) {
        return "{\"jobId\":\"" + job.jobId()
                + "\",\"summary\":\"" + summary
                + "\",\"content\":\"" + content + "\"}";
    }

    private static Exchange send(DefaultCamelContext context, String uri) {
        return context.createProducerTemplate().request(uri, TackComponentContractTest::seedRun);
    }

    private static Exchange seededExchange(DefaultCamelContext context) {
        Exchange exchange = context.getEndpoint("direct:llm").createExchange();
        seedRun(exchange);
        return exchange;
    }

    private static void seedRun(Exchange exchange) {
        Exchange seeded = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        exchange.setProperty("mission", seeded.getProperty("mission", Mission.class));
        exchange.setProperty("agentRun", seeded.getProperty("agentRun", AgentRun.class));
    }

    private static DefaultCamelContext context(ProjectionStore store, ScriptedLlm llm) throws Exception {
        DefaultCamelContext context = TestCamelContexts.quiet();
        context.setAutowiredEnabled(false);
        context.getShutdownStrategy().setTimeout(1);
        context.addComponent("tack", new TackComponent(FrameworkFixtures.primitiveExecutor(store)));
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:llm").process(exchange -> {
                    LlmJobRequest request = exchange.getMessage().getBody(LlmJobRequest.class);
                    llm.requests.add(request);
                    LlmJobEnvelope job = LlmJobEnvelope.queued(request.normalized());
                    if (llm.failConsolidation && "thought-exit".equals(request.metadata().get("primitive"))) {
                        exchange.getMessage().setBody(LlmJobResult.failed(job, "test-model", "consolidation unavailable", "", Instant.now()));
                        return;
                    }
                    String content = llm.response.apply(request, job);
                    exchange.getMessage().setBody(LlmJobResult.succeeded(job, "test-model", content, content, Instant.now()));
                });
            }
        });
        return context;
    }

    private static List<Object> types(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> row.get("type")).toList();
    }

    private static final class ScriptedLlm {
        private final List<LlmJobRequest> requests = new ArrayList<>();
        private final BiFunction<LlmJobRequest, LlmJobEnvelope, String> response;
        private boolean failConsolidation;

        private ScriptedLlm(BiFunction<LlmJobRequest, LlmJobEnvelope, String> response) {
            this.response = response;
        }
    }
}
