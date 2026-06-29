package us.nusun.llmshare.framework.tests.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.llm.InFlightReplyManager;
import us.nusun.llmshare.framework.model.InferenceLaneDefinition;
import us.nusun.llmshare.framework.model.LlmJobDeadLetter;
import us.nusun.llmshare.framework.model.LlmJobEnvelope;
import us.nusun.llmshare.framework.model.LlmJobRequest;
import us.nusun.llmshare.framework.model.LlmJobStatus;
import us.nusun.llmshare.framework.route.LlmRoute;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;
import us.nusun.llmshare.framework.tests.support.TestCamelContexts;

import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmQueueLifecycleTest {
    @Test
    void selectedLaneVerificationDoesNotRequireUnselectedLaneRoutes() throws Exception {
        DefaultCamelContext context = TestCamelContexts.quiet();
        LlmRoute route = route(context);
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:low-worker").routeId("llm-worker-low-pass").setBody(constant("ok"));
                from("direct:low-reply").routeId("llm-response-consumer-low-pass").setBody(constant("ok"));
            }
        });
        context.start();
        try {
            Exchange exchange = exchangeForLane(context, "low-pass");
            assertDoesNotThrow(() -> route.verifySelectedLlmRoutesAvailable(exchange));
        } finally {
            context.stop();
        }
    }

    @Test
    void selectedLaneVerificationFailsForRequestedLaneOnly() throws Exception {
        DefaultCamelContext context = TestCamelContexts.quiet();
        LlmRoute route = route(context);
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:low-worker").routeId("llm-worker-low-pass").setBody(constant("ok"));
                from("direct:low-reply").routeId("llm-response-consumer-low-pass").setBody(constant("ok"));
            }
        });
        context.start();
        try {
            Exchange exchange = exchangeForLane(context, "high-pass");
            LlmRoute.LlmAvailabilityException failure = assertThrows(
                    LlmRoute.LlmAvailabilityException.class,
                    () -> route.verifySelectedLlmRoutesAvailable(exchange));
            assertTrue(failure.getMessage().contains("llm-worker-high-pass"));
            assertTrue(failure.getMessage().contains("jobId="));
            assertTrue(failure.getMessage().contains("laneId=high-pass"));
            assertTrue(failure.getMessage().contains("runtimeId=tool-high"));
        } finally {
            context.stop();
        }
    }

    @Test
    void inFlightRegistrationIsDiscardedWhenRouteFailsBeforeAwait() throws Exception {
        InFlightReplyManager manager = new InFlightReplyManager();
        UUID jobId = UUID.randomUUID();
        LlmJobEnvelope job = queuedJob(jobId);
        DefaultCamelContext context = TestCamelContexts.quiet();
        context.getRegistry().bind("inFlightReplyManager", manager);
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                errorHandler(noErrorHandler());
                from("direct:register-and-fail")
                        .routeId("register-and-fail")
                        .onCompletion().onFailureOnly()
                            .bean("inFlightReplyManager", "discard(${exchangeProperty.llmJobId})")
                        .end()
                        .setProperty("llmJobId", constant(jobId))
                        .setBody(constant(job))
                        .bean("inFlightReplyManager", "register")
                        .throwException(new IllegalStateException("simulated Kafka publish failure"));
            }
        });
        context.start();
        try {
            assertThrows(CamelExecutionException.class,
                    () -> context.createProducerTemplate().requestBody("direct:register-and-fail", job));
            assertFalse(manager.contains(jobId));
            assertEquals(0, manager.inFlightCount());
        } finally {
            context.stop();
        }
    }

    @Test
    void prepareLlmJobSelectsRequestedRuntimeLaneAndPreservesEnvelopeMetadata() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        LlmRoute route = route(context);
        UUID jobId = UUID.randomUUID();
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("llmJobId", jobId);
        exchange.setProperty("personaId", "persona-a");
        exchange.setProperty("toolProfile", "source-control");
        exchange.getIn().setHeader("executionRuntime", "tool-high");
        exchange.getIn().setBody("inspect high lane");

        route.prepareLlmJob(exchange);

        LlmJobEnvelope envelope = exchange.getIn().getBody(LlmJobEnvelope.class);
        assertEquals(jobId, envelope.jobId());
        assertEquals(LlmJobStatus.QUEUED, envelope.status());
        assertEquals("tool-high", envelope.requester());
        assertEquals("high-pass", exchange.getProperty("inferenceLane", InferenceLaneDefinition.class).id());
        assertEquals("llm.requests.high", exchange.getProperty("laneRequestTopic"));
        assertEquals("llm.replies.high", exchange.getProperty("laneReplyTopic"));
        assertEquals("tool-high", envelope.metadata().get("executionRuntime"));
        assertEquals("high-pass", envelope.metadata().get("inferenceLane"));
        assertEquals("persona-a", envelope.metadata().get("personaId"));
        assertEquals("source-control", envelope.metadata().get("toolProfile"));
    }

    @Test
    void awaitLlmReplyWrapsTimeoutWithRouteContextAndCleansUp() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        Properties properties = new Properties();
        properties.setProperty("llm.reply-timeout-ms", "1");
        context.getPropertiesComponent().setOverrideProperties(properties);
        InFlightReplyManager manager = new InFlightReplyManager();
        LlmRoute route = route(context, manager);
        UUID jobId = UUID.randomUUID();
        LlmJobEnvelope job = queuedJob(jobId);
        manager.register(job);
        Exchange exchange = exchangeForLane(context, "low-pass");
        exchange.setProperty("llmJobId", jobId);
        exchange.setProperty("llmJob", job);

        LlmRoute.LlmAvailabilityException failure = assertThrows(LlmRoute.LlmAvailabilityException.class, () -> route.awaitLlmReply(exchange));

        assertTrue(failure.getMessage().contains("LLM reply failure"));
        assertTrue(failure.getMessage().contains("jobId=" + jobId));
        assertTrue(failure.getMessage().contains("laneId=low-pass"));
        assertTrue(failure.getMessage().contains("runtimeId=tool-low"));
        assertFalse(manager.contains(jobId));
    }

    @Test
    void timeoutDoesNotLeaveStaleInFlightEntry() {
        InFlightReplyManager manager = new InFlightReplyManager();
        UUID jobId = UUID.randomUUID();
        manager.register(queuedJob(jobId));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> manager.await(jobId, 1));

        assertTrue(failure.getMessage().contains(jobId.toString()));
        assertTrue(failure.getMessage().contains("after 1ms"));
        assertFalse(manager.contains(jobId));
        assertEquals(0, manager.inFlightCount());
    }

    @Test
    void deadLetterPayloadPreservesQueuedJobDebugContext() throws Exception {
        DefaultCamelContext context = TestCamelContexts.quiet();
        LlmRoute route = route(context);
        UUID jobId = UUID.randomUUID();
        LlmJobEnvelope job = queuedJob(jobId);
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("llmJob", job);
        exchange.setProperty("llmJobId", jobId);
        exchange.setProperty("llmFailureMessage", "schema validation failed");

        LlmJobDeadLetter deadLetter = route.failedQueuedJobDeadLetter(exchange);

        assertEquals(jobId, deadLetter.jobId());
        assertEquals("low-pass", deadLetter.laneId());
        assertEquals("tool-low", deadLetter.runtimeId());
        assertEquals("tool-low", deadLetter.requester());
        assertEquals("act", deadLetter.metadata().get("primitive"));
        assertEquals("schema validation failed", deadLetter.failureMessage());
        assertTrue(deadLetter.timestamp().isAfter(Instant.EPOCH));

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(deadLetter);
        assertTrue(json.contains("\"jobId\""));
        assertTrue(json.contains("\"failureMessage\":\"schema validation failed\""));
        assertTrue(json.contains("\"primitive\":\"act\""));
    }

    private static LlmRoute route(DefaultCamelContext context) {
        return route(context, new InFlightReplyManager());
    }

    private static LlmRoute route(DefaultCamelContext context, InFlightReplyManager manager) {
        LlmRoute route = new LlmRoute();
        FrameworkFixtures.set(route, "camelContext", context);
        FrameworkFixtures.set(route, "runtimes", FrameworkFixtures.executionRuntimeRegistry());
        FrameworkFixtures.set(route, "personas", FrameworkFixtures.personaCatalog());
        FrameworkFixtures.set(route, "inFlightReplyManager", manager);
        return route;
    }

    private static Exchange exchangeForLane(DefaultCamelContext context, String laneId) {
        Exchange exchange = new DefaultExchange(context);
        UUID jobId = UUID.randomUUID();
        String runtimeId = laneId.equals("high-pass") ? "tool-high" : "tool-low";
        InferenceLaneDefinition lane = FrameworkFixtures.executionRuntimeRegistry().laneRequired(laneId);
        exchange.setProperty("llmJobId", jobId);
        exchange.setProperty("executionRuntime", FrameworkFixtures.executionRuntimeRegistry().getRequired(runtimeId));
        exchange.setProperty("executionRuntimeId", runtimeId);
        exchange.setProperty("inferenceLane", lane);
        exchange.setProperty("llmJob", queuedJob(jobId));
        return exchange;
    }

    private static LlmJobEnvelope queuedJob(UUID jobId) {
        return LlmJobEnvelope.queued(new LlmJobRequest(
                jobId,
                "tool-low",
                "developer",
                0,
                "inspect the code",
                "be concise",
                null,
                Map.of(
                        "executionRuntime", "tool-low",
                        "inferenceLane", "low-pass",
                        "primitive", "act"),
                Instant.now()));
    }
}
