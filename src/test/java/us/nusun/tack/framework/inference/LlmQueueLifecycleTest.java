package us.nusun.tack.framework.inference;

import org.apache.camel.CamelExecutionException;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import us.nusun.tack.framework.inference.lane.InferenceLaneRegistry;
import us.nusun.tack.framework.inference.lane.InferenceLaneDefinition;
import us.nusun.tack.framework.run.Mission;
import us.nusun.tack.framework.inference.lane.PartitionKeyStrategy;
import us.nusun.tack.framework.run.AgentRun;
import us.nusun.tack.framework.tests.support.FrameworkFixtures;
import us.nusun.tack.framework.tests.support.TestCamelContexts;
import us.nusun.tack.framework.tests.support.TestJson;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
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
        LlmRouteSupport support = routeSupport(context);
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
            assertDoesNotThrow(() -> support.verifySelectedRoutesAvailable(exchange));
        } finally {
            context.stop();
        }
    }

    @Test
    void selectedLaneVerificationFailsForRequestedLaneOnly() throws Exception {
        DefaultCamelContext context = TestCamelContexts.quiet();
        LlmRouteSupport support = routeSupport(context);
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
            LlmRouteSupport.LlmAvailabilityException failure = assertThrows(
                    LlmRouteSupport.LlmAvailabilityException.class,
                    () -> support.verifySelectedRoutesAvailable(exchange));
            assertTrue(failure.getMessage().contains("llm-worker-high-pass"));
            assertTrue(failure.getMessage().contains("jobId="));
            assertTrue(failure.getMessage().contains("laneId=high-pass"));
            assertTrue(failure.getMessage().contains("passType=ACTION"));
            assertFalse(failure.getMessage().contains("runtime" + "Id"));
        } finally {
            context.stop();
        }
    }

    @Test
    void producerOnlyVerificationModeDoesNotRequireLocalWorkerRoute() throws Exception {
        DefaultCamelContext context = TestCamelContexts.quiet();
        Properties properties = new Properties();
        properties.setProperty("llm.local-worker-route-verification.enabled", "false");
        context.getPropertiesComponent().setOverrideProperties(properties);
        LlmRouteSupport support = routeSupport(context);
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:high-reply").routeId("llm-response-consumer-high-pass").setBody(constant("ok"));
            }
        });
        context.start();
        try {
            Exchange exchange = exchangeForLane(context, "high-pass");
            assertDoesNotThrow(() -> support.verifySelectedRoutesAvailable(exchange));
        } finally {
            context.stop();
        }
    }

    @Test
    void laneParallelismDrivesKafkaWorkerConsumerCount() throws Exception {
        DefaultCamelContext context = TestCamelContexts.quiet();
        LlmRoute route = route(context, registryWithLowLaneParallelism(3));

        context.addRoutes(route);

        String workerUri = context.getRouteDefinition("llm-worker-low-pass").getInput().getEndpointUri();
        assertTrue(workerUri.contains("consumersCount=3"));
        assertTrue(workerUri.contains("maxPollRecords=1"));
    }

    @Test
    void inFlightRegistrationIsDiscardedWhenRouteFailsBeforeAwait() throws Exception {
        InFlightReplyManager manager = new InFlightReplyManager();
        UUID jobId = UUID.randomUUID();
        LlmJobEnvelope job = queuedJob(jobId);
        DefaultCamelContext context = TestCamelContexts.quiet();
        var support = routeSupport(context, manager);
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                errorHandler(noErrorHandler());
                from("direct:register-and-fail")
                        .routeId("register-and-fail")
                        .onCompletion().onFailureOnly()
                            .bean(support, "discardReply")
                        .end()
                        .process(exchange -> FrameworkFixtures.restoreJob(exchange, job))
                        .setBody(constant(job))
                        .bean(support, "registerReply")
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
    void rawPromptIsRejectedBeforeQueueingEvenWithLegacyRoutingHeaders() {
        var exchange = new DefaultExchange(TestCamelContexts.quiet());
        exchange.getMessage().setHeader("lane", "high-pass");
        exchange.getMessage().setBody("raw prompt");
        var jobs = jobFactory();
        var failure = assertThrows(IllegalArgumentException.class, () -> jobs.prepare(exchange));
        assertTrue(failure.getMessage().contains("requires an LlmJobEnvelope"));
        assertEquals(null, LlmExecutionContext.on(exchange));
    }

    @Test
    void directLlmDefaultsToLowPassSynthesisWhenLaneAndPassAreUnset() {
        Exchange exchange = new DefaultExchange(TestCamelContexts.quiet());
        exchange.getMessage().setBody(new LlmJobEnvelope(null, null, null, null, null, 0, "synthesize this",
                null, null, Map.of(), null));

        jobFactory().prepare(exchange);

        LlmJobEnvelope envelope = exchange.getIn().getBody(LlmJobEnvelope.class);
        assertEquals("low-pass", envelope.laneId());
        assertEquals(LlmPassType.SYNTHESIS, envelope.passType());
        assertEquals("low-pass", envelope.metadata().get("laneId"));
        assertEquals("SYNTHESIS", envelope.metadata().get("passType"));
    }

    @Test
    void prepareExistingLlmJobEnvelopeIgnoresStaleExchangePassTypeProperty() {
        Exchange exchange = new DefaultExchange(TestCamelContexts.quiet());
        exchange.setProperty("passType", LlmPassType.ACTION);
        exchange.setProperty("laneId", "high-pass");
        exchange.getIn().setBody(new LlmJobEnvelope(
                null,
                "low-pass",
                LlmPassType.SYNTHESIS,
                "act:assignment-review",
                "Persona A",
                0,
                "synthesize step result",
                "system",
                "classpath:schemas/thought-content-result-schema.json",
                Map.of("personaId", FrameworkFixtures.PERSONA_A),
                null));

        jobFactory().prepare(exchange);

        LlmJobEnvelope envelope = exchange.getIn().getBody(LlmJobEnvelope.class);
        assertEquals("low-pass", envelope.laneId());
        assertEquals(LlmPassType.SYNTHESIS, envelope.passType());
        assertEquals("low-pass", envelope.metadata().get("laneId"));
        assertEquals("SYNTHESIS", envelope.metadata().get("passType"));
    }

    @Test
    void defaultAgentRunPartitionKeyUsesAgentRunThenMissionThenJob() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        LlmJobFactory jobs = jobFactory();
        UUID jobId = UUID.randomUUID();
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("agentRun", new AgentRun("run-1", "mission-1", "persona-a", "routine-a", Map.of()));
        exchange.setProperty("mission", new Mission("mission-1", "goal", List.of(), "active", Instant.now()));
        exchange.getMessage().setBody(queuedJob(jobId));

        jobs.prepare(exchange);
        assertEquals("run-1", jobs.requestKafkaKey(exchange));

        Exchange missionFallback = new DefaultExchange(context);
        missionFallback.setProperty("mission", new Mission("mission-2", "goal", List.of(), "active", Instant.now()));
        missionFallback.getMessage().setBody(queuedJob(jobId));
        jobs.prepare(missionFallback);
        assertEquals("mission-2", jobs.requestKafkaKey(missionFallback));

        Exchange jobFallback = new DefaultExchange(context);
        jobFallback.getMessage().setBody(queuedJob(jobId));
        jobs.prepare(jobFallback);
        assertEquals(jobId.toString(), jobs.requestKafkaKey(jobFallback));
    }

    @Test
    void missionPartitionKeyUsesMissionThenAgentRunThenJob() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        LlmJobFactory jobs = jobFactory(registryWithPartitionStrategy(PartitionKeyStrategy.MISSION));
        UUID jobId = UUID.randomUUID();
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("agentRun", new AgentRun("run-1", "mission-1", "persona-a", "routine-a", Map.of()));
        exchange.setProperty("mission", new Mission("mission-1", "goal", List.of(), "active", Instant.now()));
        exchange.getMessage().setBody(queuedJob(jobId));

        jobs.prepare(exchange);
        assertEquals("mission-1", jobs.requestKafkaKey(exchange));

        Exchange runFallback = new DefaultExchange(context);
        runFallback.setProperty("agentRun", new AgentRun("run-2", "mission-2", "persona-a", "routine-a", Map.of()));
        runFallback.getMessage().setBody(queuedJob(jobId));
        jobs.prepare(runFallback);
        assertEquals("run-2", jobs.requestKafkaKey(runFallback));

        Exchange jobFallback = new DefaultExchange(context);
        jobFallback.getMessage().setBody(queuedJob(jobId));
        jobs.prepare(jobFallback);
        assertEquals(jobId.toString(), jobs.requestKafkaKey(jobFallback));
    }

    @Test
    void jobPartitionKeyAlwaysUsesJobId() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        LlmJobFactory jobs = jobFactory(registryWithPartitionStrategy(PartitionKeyStrategy.JOB));
        UUID jobId = UUID.randomUUID();
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("agentRun", new AgentRun("run-1", "mission-1", "persona-a", "routine-a", Map.of()));
        exchange.setProperty("mission", new Mission("mission-1", "goal", List.of(), "active", Instant.now()));
        exchange.getMessage().setBody(queuedJob(jobId));

        jobs.prepare(exchange);

        assertEquals(jobId.toString(), jobs.requestKafkaKey(exchange));
    }

    @Test
    void prepareLlmJobPreservesDifferentLaneModelsWithSharedEndpoint() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        LlmJobFactory jobs = jobFactory(registryWithModels("low-lane-model", "high-lane-model"));
        Exchange low = new DefaultExchange(context);
        low.getMessage().setBody(queuedJob(UUID.randomUUID(), "low-pass", LlmPassType.ACTION, Map.of()));
        Exchange high = new DefaultExchange(context);
        high.getMessage().setBody(queuedJob(UUID.randomUUID(), "high-pass", LlmPassType.ACTION, Map.of()));

        jobs.prepare(low);
        jobs.prepare(high);

        assertEquals("low-lane-model", LlmExecutionContext.require(low).model());
        assertEquals("high-lane-model", LlmExecutionContext.require(high).model());
        assertEquals("low-lane-model", low.getIn().getBody(LlmJobEnvelope.class).metadata().get("model"));
        assertEquals("high-lane-model", high.getIn().getBody(LlmJobEnvelope.class).metadata().get("model"));
    }

    @Test
    void globalModelRemainsFallbackWhenLaneDoesNotSelectModel() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        LlmJobFactory jobs = FrameworkFixtures.jobFactory(registryWithModels("", ""), "global-model", false);
        Exchange exchange = new DefaultExchange(context);
        exchange.getMessage().setBody(queuedJob(UUID.randomUUID()));

        jobs.prepare(exchange);

        assertEquals("global-model", LlmExecutionContext.require(exchange).model());
        assertEquals("global-model", exchange.getIn().getBody(LlmJobEnvelope.class).metadata().get("model"));
    }

    @Test
    void awaitReplyWrapsTimeoutWithRouteContextAndCleansUp() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        Properties properties = new Properties();
        properties.setProperty("llm.reply-timeout-ms", "1");
        context.getPropertiesComponent().setOverrideProperties(properties);
        InFlightReplyManager manager = new InFlightReplyManager();
        LlmRouteSupport support = routeSupport(context, manager);
        UUID jobId = UUID.randomUUID();
        LlmJobEnvelope job = queuedJob(jobId);
        manager.register(job);
        Exchange exchange = new DefaultExchange(context);
        FrameworkFixtures.restoreJob(exchange, job);

        LlmRouteSupport.LlmAvailabilityException failure = assertThrows(
                LlmRouteSupport.LlmAvailabilityException.class,
                () -> support.awaitReply(exchange));

        assertTrue(failure.getMessage().contains("LLM reply failure"));
        assertTrue(failure.getMessage().contains("jobId=" + jobId));
        assertTrue(failure.getMessage().contains("laneId=low-pass"));
        assertTrue(failure.getMessage().contains("passType=ACTION"));
        assertFalse(failure.getMessage().contains("runtime" + "Id"));
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
        UUID jobId = UUID.randomUUID();
        LlmJobEnvelope job = queuedJob(jobId, Map.of("model", "selected-low-model"));
        Exchange exchange = new DefaultExchange(context);
        FrameworkFixtures.restoreJob(exchange, job);
        exchange.setProperty("llmFailureMessage", "schema validation failed");

        LlmJobDeadLetter deadLetter = deadLetters().failedQueuedJobDeadLetter(exchange);

        assertEquals(jobId, deadLetter.jobId());
        assertEquals("low-pass", deadLetter.laneId());
        assertEquals(LlmPassType.ACTION, deadLetter.passType());
        assertEquals("developer", deadLetter.requester());
        assertEquals("selected-low-model", deadLetter.model());
        assertEquals("act", deadLetter.metadata().get("primitive"));
        assertEquals("schema validation failed", deadLetter.failureMessage());
        assertEquals(job.requestedAt(), deadLetter.requestedAt());
        assertTrue(deadLetter.timestamp().isAfter(Instant.EPOCH));

        String json = TestJson.write(deadLetter);
        assertTrue(json.contains("\"jobId\""));
        assertTrue(json.contains("\"laneId\":\"low-pass\""));
        assertTrue(json.contains("\"passType\":\"ACTION\""));
        assertTrue(json.contains("\"failureMessage\":\"schema validation failed\""));
        assertTrue(json.contains("\"primitive\":\"act\""));
        assertFalse(json.contains("runtime" + "Id"));
    }

    @Test
    void deadLetterFallsBackToJobLaneWhenLanePropertyIsMissing() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        LlmJobEnvelope job = queuedJob(UUID.randomUUID(), "high-pass", LlmPassType.SYNTHESIS, Map.of("model", "metadata-model"));
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("llmJob", job);
        exchange.setProperty("llmFailureMessage", "failed");

        LlmJobDeadLetter deadLetter = deadLetters().failedQueuedJobDeadLetter(exchange);

        assertEquals("high-pass", deadLetter.laneId());
        assertEquals(LlmPassType.SYNTHESIS, deadLetter.passType());
        assertEquals("metadata-model", deadLetter.model());
        assertEquals("failed", deadLetter.failureMessage());
    }

    @Test
    void deadLetterUsesUnknownFallbacksWithoutObjectToStringLaneIds() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        UUID jobId = UUID.randomUUID();
        LlmJobEnvelope job = new LlmJobEnvelope(
                jobId,
                LlmJobStatus.QUEUED,
                null,
                null,
                "developer",
                "developer",
                0,
                "inspect the code",
                "be concise",
                null,
                Map.of("laneId", FrameworkFixtures.inferenceLaneRegistry().getRequired("low-pass")),
                Instant.now());
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("llmJob", job);

        LlmJobDeadLetter deadLetter = deadLetters().failedQueuedJobDeadLetter(exchange);

        assertEquals("unknown", deadLetter.laneId());
        assertEquals("Unknown LLM queue failure", deadLetter.failureMessage());
        assertFalse(deadLetter.laneId().contains("InferenceLaneDefinition"));
    }

    @Test
    void deadLetterMetadataPreservesJsonSafeScalarsAndNulls() throws Exception {
        DefaultCamelContext context = TestCamelContexts.quiet();
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("string", "value");
        metadata.put("integer", 7);
        metadata.put("decimal", 2.5);
        metadata.put("boolean", true);
        metadata.put("nullValue", null);
        LlmJobEnvelope job = new LlmJobEnvelope(
                UUID.randomUUID(),
                LlmJobStatus.QUEUED,
                "low-pass",
                LlmPassType.SYNTHESIS,
                "developer",
                "developer",
                0,
                "inspect the code",
                "be concise",
                null,
                metadata,
                Instant.now());
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("llmJob", job);

        LlmJobDeadLetter deadLetter = deadLetters().failedQueuedJobDeadLetter(exchange);

        assertEquals("value", deadLetter.metadata().get("string"));
        assertEquals(7, deadLetter.metadata().get("integer"));
        assertEquals(2.5, deadLetter.metadata().get("decimal"));
        assertEquals(true, deadLetter.metadata().get("boolean"));
        assertTrue(deadLetter.metadata().containsKey("nullValue"));
        assertEquals(null, deadLetter.metadata().get("nullValue"));
        String json = TestJson.write(deadLetter);
        assertTrue(json.contains("\"nullValue\":null"));
    }

    @Test
    void deadLetterMetadataNormalizesNestedMapsAndListsRecursively() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        InferenceLaneDefinition lane = FrameworkFixtures.inferenceLaneRegistry().getRequired("low-pass");
        LinkedHashMap<String, Object> nested = new LinkedHashMap<>();
        nested.put("lane", lane);
        nested.put("items", List.of("ok", lane, Map.of("laneAgain", lane)));
        LlmJobEnvelope job = queuedJob(UUID.randomUUID(), Map.of("nested", nested));
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("llmJob", job);

        LlmJobDeadLetter deadLetter = deadLetters().failedQueuedJobDeadLetter(exchange);

        Map<?, ?> normalizedNested = (Map<?, ?>) deadLetter.metadata().get("nested");
        assertEquals("low-pass", normalizedNested.get("lane"));
        List<?> items = (List<?>) normalizedNested.get("items");
        assertEquals("ok", items.get(0));
        assertEquals("low-pass", items.get(1));
        assertEquals("low-pass", ((Map<?, ?>) items.get(2)).get("laneAgain"));
    }

    @Test
    void deadLetterMetadataConvertsInferenceLaneDefinitionToLaneId() throws Exception {
        DefaultCamelContext context = TestCamelContexts.quiet();
        InferenceLaneDefinition lane = FrameworkFixtures.inferenceLaneRegistry().getRequired("high-pass");
        LlmJobEnvelope job = queuedJob(UUID.randomUUID(), Map.of("laneObject", lane));
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("llmJob", job);

        LlmJobDeadLetter deadLetter = deadLetters().failedQueuedJobDeadLetter(exchange);

        assertEquals("high-pass", deadLetter.metadata().get("laneObject"));
        String json = TestJson.write(deadLetter);
        assertFalse(json.contains("InferenceLaneDefinition"));
        assertFalse(json.contains("requestTopic"));
    }

    @Test
    void deadLetterMetadataConvertsKnownFrameworkObjectsToIds() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        LlmJobEnvelope job = queuedJob(UUID.randomUUID(), Map.of(
                "laneObject", FrameworkFixtures.inferenceLaneRegistry().getRequired("high-pass"),
                "personaObject", FrameworkFixtures.personaCatalog().getRequired(FrameworkFixtures.PERSONA_A)));
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("llmJob", job);

        LlmJobDeadLetter deadLetter = deadLetters().failedQueuedJobDeadLetter(exchange);

        assertEquals("high-pass", deadLetter.metadata().get("laneObject"));
        assertEquals(FrameworkFixtures.PERSONA_A, deadLetter.metadata().get("personaObject"));
    }

    @Test
    void deadLetterMetadataUnknownObjectsUseBoundedClassQualifiedString() throws Exception {
        DefaultCamelContext context = TestCamelContexts.quiet();
        UnknownMetadataValue value = new UnknownMetadataValue("secret-ish-object-graph");
        LlmJobEnvelope job = queuedJob(UUID.randomUUID(), Map.of("unknown", value));
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("llmJob", job);

        LlmJobDeadLetter deadLetter = deadLetters().failedQueuedJobDeadLetter(exchange);

        String normalized = (String) deadLetter.metadata().get("unknown");
        assertTrue(normalized.contains(UnknownMetadataValue.class.getName()));
        assertTrue(normalized.contains("secret-ish-object-graph"));
        String json = TestJson.write(deadLetter);
        assertFalse(json.contains("\"value\""));
    }

    @Test
    void deadLetterMetadataBoundsVeryLongStringsAndFallbackObjects() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        String longString = "p".repeat(2000);
        LlmJobEnvelope job = queuedJob(UUID.randomUUID(), Map.of(
                "longString", longString,
                "longObject", new UnknownMetadataValue("x".repeat(2000))));
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("llmJob", job);

        LlmJobDeadLetter deadLetter = deadLetters().failedQueuedJobDeadLetter(exchange);

        String normalizedString = (String) deadLetter.metadata().get("longString");
        String normalizedObject = (String) deadLetter.metadata().get("longObject");
        assertTrue(normalizedString.length() <= 512);
        assertTrue(normalizedObject.length() <= 512);
        assertTrue(normalizedString.endsWith("...(truncated, 2000 chars)"));
        assertTrue(normalizedObject.contains("...(truncated, "));
    }

    @Test
    void failedWorkerResultPreservesSelectedJobModelTimingAndRootCauseContext() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        Properties properties = new Properties();
        properties.setProperty("llm.model-default.openai.model", "global-model");
        context.getPropertiesComponent().setOverrideProperties(properties);
        Instant requestedAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant startedAt = Instant.parse("2026-01-01T00:00:05Z");
        UUID jobId = UUID.randomUUID();
        LlmJobEnvelope job = FrameworkFixtures.queuedJob(new LlmJobEnvelope(
                jobId,
                "high-pass",
                LlmPassType.ACTION,
                "developer",
                "developer",
                0,
                "inspect the code",
                "be concise",
                null,
                Map.of(
                        "laneId", "high-pass",
                        "passType", "ACTION",
                        "model", "selected-high-model",
                        "toolProfile", FrameworkFixtures.PROFILE_GAMMA),
                requestedAt));
        Exchange exchange = new DefaultExchange(context);
        FrameworkFixtures.restoreJob(exchange, job);
        exchange.setProperty("llmStartedAt", startedAt);
        exchange.setProperty(Exchange.EXCEPTION_CAUGHT, new IllegalStateException("agent failed", new RuntimeException("socket closed")));

        LlmJobResult result = deadLetters().failedLlmResult(exchange);

        assertEquals(jobId, result.jobId());
        assertEquals(LlmJobStatus.FAILED, result.status());
        assertEquals("high-pass", result.laneId());
        assertEquals(LlmPassType.ACTION, result.passType());
        assertEquals("developer", result.requester());
        assertEquals("selected-high-model", result.model());
        assertEquals(requestedAt, result.requestedAt());
        assertEquals(startedAt, result.startedAt());
        assertTrue(result.error().contains("agent failed"));
        assertTrue(result.error().contains("socket closed"));
    }

    private static LlmRoute route(DefaultCamelContext context) {
        return route(context, new InFlightReplyManager());
    }

    private static LlmRoute route(DefaultCamelContext context, InFlightReplyManager manager) {
        return route(context, manager, FrameworkFixtures.inferenceLaneRegistry());
    }

    private static LlmRoute route(DefaultCamelContext context, InferenceLaneRegistry registry) {
        return route(context, new InFlightReplyManager(), registry);
    }

    private static LlmRoute route(DefaultCamelContext context, InFlightReplyManager manager, InferenceLaneRegistry registry) {
        LlmDeadLetterFactory deadLetters = deadLetters();
        LlmRoute route = new LlmRoute();
        FrameworkFixtures.set(route, "lanes", registry);
        FrameworkFixtures.set(route, "jobFactory", jobFactory(registry));
        FrameworkFixtures.set(route, "deadLetterFactory", deadLetters);
        FrameworkFixtures.set(route, "routeSupport", new LlmRouteSupport(context, manager, deadLetters));
        return route;
    }

    private static LlmRouteSupport routeSupport(DefaultCamelContext context) {
        return routeSupport(context, new InFlightReplyManager());
    }

    private static LlmRouteSupport routeSupport(DefaultCamelContext context, InFlightReplyManager manager) {
        return new LlmRouteSupport(context, manager, deadLetters());
    }

    private static LlmJobFactory jobFactory() {
        return FrameworkFixtures.jobFactory();
    }

    private static LlmJobFactory jobFactory(InferenceLaneRegistry registry) {
        return FrameworkFixtures.jobFactory(registry, "test-model", false);
    }

    private static LlmDeadLetterFactory deadLetters() {
        return new LlmDeadLetterFactory();
    }

    private static InferenceLaneRegistry registryWithModels(String lowLaneModel, String highLaneModel) {
        return new InferenceLaneRegistry(List.of(
                InferenceLaneDefinition.builder("low-pass")
                        .requestTopic("test.llm.requests.low")
                        .responseTopic("test.llm.replies.low")
                        .parallelism(1)
                        .endpoint("http://shared-openai.example/v1")
                        .model(lowLaneModel)
                        .build(),
                InferenceLaneDefinition.builder("high-pass")
                        .requestTopic("test.llm.requests.high")
                        .responseTopic("test.llm.replies.high")
                        .parallelism(1)
                        .endpoint("http://shared-openai.example/v1")
                        .model(highLaneModel)
                        .build()));
    }

    private static InferenceLaneRegistry registryWithLowLaneParallelism(int parallelism) {
        return new InferenceLaneRegistry(List.of(
                InferenceLaneDefinition.builder("low-pass")
                        .requestTopic("test.llm.requests.low")
                        .responseTopic("test.llm.replies.low")
                        .parallelism(parallelism)
                        .build()));
    }

    private static InferenceLaneRegistry registryWithPartitionStrategy(PartitionKeyStrategy strategy) {
        return new InferenceLaneRegistry(List.of(InferenceLaneDefinition.builder("low-pass")
                .requestTopic("test.llm.requests.low")
                .responseTopic("test.llm.replies.low")
                .partitionKeyStrategy(strategy)
                .build()));
    }

    private static Exchange exchangeForLane(DefaultCamelContext context, String laneId) {
        Exchange exchange = new DefaultExchange(context);
        FrameworkFixtures.restoreJob(exchange, queuedJob(UUID.randomUUID(), laneId, LlmPassType.ACTION, Map.of()));
        return exchange;
    }

    private static LlmJobEnvelope queuedJob(UUID jobId) {
        return queuedJob(jobId, Map.of());
    }

    private static LlmJobEnvelope queuedJob(UUID jobId, Map<String, Object> metadata) {
        return queuedJob(jobId, "low-pass", LlmPassType.ACTION, metadata);
    }

    private static LlmJobEnvelope queuedJob(UUID jobId, String laneId, LlmPassType passType, Map<String, Object> metadata) {
        return queuedJob(jobId, laneId, passType, metadata, Instant.now());
    }

    private static LlmJobEnvelope queuedJob(UUID jobId, String laneId, LlmPassType passType, Map<String, Object> metadata, Instant requestedAt) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
        if (metadata != null) {
            merged.putAll(metadata);
        }
        if (laneId != null) {
            merged.putIfAbsent("laneId", laneId);
        }
        if (passType != null) {
            merged.putIfAbsent("passType", passType.name());
        }
        merged.putIfAbsent("primitive", "act");
        return FrameworkFixtures.queuedJob(new LlmJobEnvelope(
                jobId,
                laneId,
                passType,
                "developer",
                "developer",
                0,
                "inspect the code",
                "be concise",
                null,
                Map.copyOf(merged),
                requestedAt));
    }

    private record UnknownMetadataValue(String value) {
        @Override
        public String toString() {
            return "UnknownMetadataValue[value=" + value + "]";
        }
    }
}
