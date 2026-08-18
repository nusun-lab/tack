package us.nusun.llmshare.framework.tests.unit;

import org.apache.camel.CamelExecutionException;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.inventory.InferenceLaneRegistry;
import us.nusun.llmshare.framework.llm.InFlightReplyManager;
import us.nusun.llmshare.framework.llm.LlmDeadLetterFactory;
import us.nusun.llmshare.framework.llm.LlmJobFactory;
import us.nusun.llmshare.framework.llm.LlmRouteSupport;
import us.nusun.llmshare.framework.model.InferenceLaneDefinition;
import us.nusun.llmshare.framework.model.LlmJobDeadLetter;
import us.nusun.llmshare.framework.model.LlmJob;
import us.nusun.llmshare.framework.model.LlmJobResult;
import us.nusun.llmshare.framework.model.LlmJobStatus;
import us.nusun.llmshare.framework.model.Mission;
import us.nusun.llmshare.framework.model.PartitionKeyStrategy;
import us.nusun.llmshare.framework.route.LlmRoute;
import us.nusun.llmshare.framework.state.AgentRun;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;
import us.nusun.llmshare.framework.tests.support.TestCamelContexts;
import us.nusun.llmshare.framework.tests.support.TestJson;

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
            assertDoesNotThrow(() -> support.verifySelectedRoutesAvailable(exchange.getProperty("inferenceLane", InferenceLaneDefinition.class), exchange));
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
                    () -> support.verifySelectedRoutesAvailable(exchange.getProperty("inferenceLane", InferenceLaneDefinition.class), exchange));
            assertTrue(failure.getMessage().contains("llm-worker-high-pass"));
            assertTrue(failure.getMessage().contains("jobId="));
            assertTrue(failure.getMessage().contains("laneId=high-pass"));
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
            assertDoesNotThrow(() -> support.verifySelectedRoutesAvailable(exchange.getProperty("inferenceLane", InferenceLaneDefinition.class), exchange));
        } finally {
            context.stop();
        }
    }

    @Test
    void finishActToolTagIsOnlyAttachedToActAgentInvocations() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        LlmRouteSupport support = routeSupport(context);

        LlmJob actJob = job(UUID.randomUUID(), Map.of("primitive", "act"));
        Exchange actExchange = new DefaultExchange(context);
        actExchange.setProperty("llmJob", actJob);
        support.prepareAgentCall(actJob, actExchange);

        LlmJob decideJob = job(UUID.randomUUID(), Map.of("primitive", "decide"));
        Exchange decideExchange = new DefaultExchange(context);
        decideExchange.setProperty("llmJob", decideJob);
        support.prepareAgentCall(decideJob, decideExchange);

        assertTrue(actExchange.getProperty("agentInvocationUri", String.class).contains("tags=" + LlmRouteSupport.ACT_TOOL_TAG));
        assertFalse(decideExchange.getProperty("agentInvocationUri", String.class).contains("tags="));
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
        LlmJob job = job(jobId);
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
    void prepareLlmJobSelectsItsLaneAndTransportMetadata() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        UUID jobId = UUID.randomUUID();
        Exchange exchange = new DefaultExchange(context);
        LlmJob job = job(jobId, "high-pass", Map.of(
                "personaId", "persona-a",
                "toolProfile", FrameworkFixtures.PROFILE_GAMMA));
        exchange.getIn().setBody(job);

        jobFactory().prepare(exchange);

        assertEquals(job, exchange.getIn().getBody(LlmJob.class));
        assertEquals("high-pass", exchange.getProperty("inferenceLane", InferenceLaneDefinition.class).id());
        assertEquals("llm.requests.high", exchange.getProperty("laneRequestTopic"));
        assertEquals("llm.replies.high", exchange.getProperty("laneReplyTopic"));
        assertEquals(jobId.toString(), exchange.getProperty("llmPartitionKey"));
        assertEquals("persona-a", job.metadata().get("personaId"));
        assertEquals(FrameworkFixtures.PROFILE_GAMMA, job.metadata().get("toolProfile"));
    }

    @Test
    void directLlmRejectsRawPromptBodies() {
        Exchange exchange = new DefaultExchange(TestCamelContexts.quiet());
        exchange.getIn().setBody("synthesize this");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> jobFactory().prepare(exchange));
        assertEquals("direct:llm requires an LlmJob body", failure.getMessage());
    }

    @Test
    void prepareLlmJobPreservesLaneAndToolProfile() {
        Exchange exchange = new DefaultExchange(TestCamelContexts.quiet());
        exchange.getIn().setBody(new LlmJob(
                UUID.randomUUID(),
                "high-pass",
                "inspect assigned work",
                "system",
                null,
                Map.of("personaId", FrameworkFixtures.PERSONA_A, "toolProfile", FrameworkFixtures.PROFILE_GAMMA),
                Instant.now()));

        jobFactory().prepare(exchange);

        LlmJob job = exchange.getIn().getBody(LlmJob.class);
        assertEquals("high-pass", job.laneId());
        assertEquals(FrameworkFixtures.PROFILE_GAMMA, job.metadata().get("toolProfile"));
    }

    @Test
    void defaultAgentRunPartitionKeyUsesAgentRunThenMissionThenJob() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        UUID jobId = UUID.randomUUID();
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("agentRun", new AgentRun("run-1", "mission-1", "persona-a", "routine-a", Map.of()));
        exchange.setProperty("mission", new Mission("mission-1", "goal", List.of(), "active", Map.of(), Instant.now()));
        exchange.getIn().setBody(job(jobId));

        jobFactory().prepare(exchange);
        assertEquals("run-1", exchange.getProperty("llmPartitionKey"));

        Exchange missionFallback = new DefaultExchange(context);
        missionFallback.setProperty("mission", new Mission("mission-2", "goal", List.of(), "active", Map.of(), Instant.now()));
        missionFallback.getIn().setBody(job(jobId));
        jobFactory().prepare(missionFallback);
        assertEquals("mission-2", missionFallback.getProperty("llmPartitionKey"));

        Exchange jobFallback = new DefaultExchange(context);
        jobFallback.getIn().setBody(job(jobId));
        jobFactory().prepare(jobFallback);
        assertEquals(jobId.toString(), jobFallback.getProperty("llmPartitionKey"));
    }

    @Test
    void missionPartitionKeyUsesMissionThenAgentRunThenJob() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        LlmJobFactory jobs = jobFactory(registryWithPartitionStrategy(PartitionKeyStrategy.MISSION));
        UUID jobId = UUID.randomUUID();
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("agentRun", new AgentRun("run-1", "mission-1", "persona-a", "routine-a", Map.of()));
        exchange.setProperty("mission", new Mission("mission-1", "goal", List.of(), "active", Map.of(), Instant.now()));
        exchange.getIn().setBody(job(jobId));

        jobs.prepare(exchange);
        assertEquals("mission-1", exchange.getProperty("llmPartitionKey"));

        Exchange runFallback = new DefaultExchange(context);
        runFallback.setProperty("agentRun", new AgentRun("run-2", "mission-2", "persona-a", "routine-a", Map.of()));
        runFallback.getIn().setBody(job(jobId));
        jobs.prepare(runFallback);
        assertEquals("run-2", runFallback.getProperty("llmPartitionKey"));

        Exchange jobFallback = new DefaultExchange(context);
        jobFallback.getIn().setBody(job(jobId));
        jobs.prepare(jobFallback);
        assertEquals(jobId.toString(), jobFallback.getProperty("llmPartitionKey"));
    }

    @Test
    void jobPartitionKeyAlwaysUsesJobId() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        LlmJobFactory jobs = jobFactory(registryWithPartitionStrategy(PartitionKeyStrategy.JOB));
        UUID jobId = UUID.randomUUID();
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("agentRun", new AgentRun("run-1", "mission-1", "persona-a", "routine-a", Map.of()));
        exchange.setProperty("mission", new Mission("mission-1", "goal", List.of(), "active", Map.of(), Instant.now()));
        exchange.getIn().setBody(job(jobId));

        jobs.prepare(exchange);

        assertEquals(jobId.toString(), exchange.getProperty("llmPartitionKey"));
    }

    @Test
    void kafkaKeyHelpersUsePartitionKeyForRequestsAndJobIdForReplies() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        UUID jobId = UUID.randomUUID();
        Exchange exchange = new DefaultExchange(context);
        LlmJob job = job(jobId);
        exchange.setProperty("llmPartitionKey", "agent/run:1?stable=true");
        exchange.setProperty("llmJob", job);

        assertEquals("agent/run:1?stable=true", jobFactory().requestKafkaKey(exchange));
        assertEquals(jobId, jobFactory().jobIdKafkaKey(exchange));

        UUID overrideJobId = UUID.randomUUID();
        exchange.setProperty("llmJobId", overrideJobId);
        assertEquals(overrideJobId, jobFactory().jobIdKafkaKey(exchange));
    }

    @Test
    void prepareLlmJobPreservesDifferentLaneModelsWithSharedEndpoint() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        LlmJobFactory jobs = jobFactory(registryWithModels("low-lane-model", "high-lane-model"));
        Exchange low = new DefaultExchange(context);
        low.getIn().setBody(job(UUID.randomUUID(), "low-pass", Map.of()));
        Exchange high = new DefaultExchange(context);
        high.getIn().setBody(job(UUID.randomUUID(), "high-pass", Map.of()));

        jobs.prepare(low);
        jobs.prepare(high);

        assertEquals("low-lane-model", low.getProperty("modelId"));
        assertEquals("high-lane-model", high.getProperty("modelId"));
    }

    @Test
    void globalModelRemainsFallbackWhenLaneDoesNotSelectModel() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        Properties properties = new Properties();
        properties.setProperty("llm.model-default.openai.model", "global-model");
        context.getPropertiesComponent().setOverrideProperties(properties);
        LlmJobFactory jobs = jobFactory(registryWithModels("", ""));
        Exchange exchange = new DefaultExchange(context);
        exchange.getIn().setBody(job(UUID.randomUUID()));

        jobs.prepare(exchange);

        assertEquals("global-model", exchange.getProperty("modelId"));
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
        LlmJob job = job(jobId);
        manager.register(job);
        Exchange exchange = exchangeForLane(context, "low-pass");
        exchange.setProperty("llmJobId", jobId);
        exchange.setProperty("llmJob", job);

        LlmRouteSupport.LlmAvailabilityException failure = assertThrows(
                LlmRouteSupport.LlmAvailabilityException.class,
                () -> support.awaitReply(jobId, exchange));

        assertTrue(failure.getMessage().contains("LLM reply failure"));
        assertTrue(failure.getMessage().contains("jobId=" + jobId));
        assertTrue(failure.getMessage().contains("laneId=low-pass"));
        assertFalse(failure.getMessage().contains("runtime" + "Id"));
        assertFalse(manager.contains(jobId));
    }

    @Test
    void timeoutDoesNotLeaveStaleInFlightEntry() {
        InFlightReplyManager manager = new InFlightReplyManager();
        UUID jobId = UUID.randomUUID();
        manager.register(job(jobId));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> manager.await(jobId, 1));

        assertTrue(failure.getMessage().contains(jobId.toString()));
        assertTrue(failure.getMessage().contains("after 1ms"));
        assertFalse(manager.contains(jobId));
        assertEquals(0, manager.inFlightCount());
    }

    @Test
    void deadLetterFactoryCamelFacingMethodsAreNotOverloaded() {
        for (String name : List.of("failedJobDeadLetter", "failedLlmResult", "contextSummary", "modelId")) {
            long count = java.util.Arrays.stream(LlmDeadLetterFactory.class.getMethods())
                    .filter(method -> method.getDeclaringClass().equals(LlmDeadLetterFactory.class))
                    .filter(method -> method.getName().equals(name))
                    .count();
            assertEquals(1, count, name + " must stay unambiguous for Camel bean method resolution");
        }
    }

    @Test
    void deadLetterPayloadPreservesJobDebugContext() throws Exception {
        DefaultCamelContext context = TestCamelContexts.quiet();
        UUID jobId = UUID.randomUUID();
        LlmJob job = job(jobId);
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("llmJob", job);
        exchange.setProperty("llmJobId", jobId);
        exchange.setProperty("modelId", "selected-low-model");
        exchange.setProperty("llmFailureMessage", "schema validation failed");

        LlmJobDeadLetter deadLetter = deadLetters().failedJobDeadLetter(exchange);

        assertEquals(jobId, deadLetter.jobId());
        assertEquals("low-pass", deadLetter.laneId());
        assertEquals("selected-low-model", deadLetter.model());
        assertEquals("act", deadLetter.metadata().get("primitive"));
        assertEquals("schema validation failed", deadLetter.failureMessage());
        assertEquals(job.requestedAt(), deadLetter.requestedAt());
        assertTrue(deadLetter.timestamp().isAfter(Instant.EPOCH));

        String json = TestJson.write(deadLetter);
        assertTrue(json.contains("\"jobId\""));
        assertTrue(json.contains("\"laneId\":\"low-pass\""));
        assertTrue(json.contains("\"failureMessage\":\"schema validation failed\""));
        assertTrue(json.contains("\"primitive\":\"act\""));
        assertFalse(json.contains("runtime" + "Id"));
    }

    @Test
    void deadLetterPrefersTypedLaneDefinitionOverJobLane() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        LlmJob job = job(UUID.randomUUID(), "low-pass", Map.of());
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("llmJob", job);
        exchange.setProperty("inferenceLane", FrameworkFixtures.inferenceLaneRegistry().getRequired("high-pass"));

        LlmJobDeadLetter deadLetter = deadLetters().failedJobDeadLetter(exchange);

        assertEquals("high-pass", deadLetter.laneId());
    }

    @Test
    void deadLetterAcceptsStringLaneProperty() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        LlmJob job = job(UUID.randomUUID(), "low-pass", Map.of());
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("llmJob", job);
        exchange.setProperty("inferenceLane", "string-lane");

        LlmJobDeadLetter deadLetter = deadLetters().failedJobDeadLetter(exchange);

        assertEquals("string-lane", deadLetter.laneId());
    }

    @Test
    void deadLetterFallsBackToJobLaneWhenLanePropertyIsMissing() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        LlmJob job = job(UUID.randomUUID(), "high-pass", Map.of());
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("llmJob", job);
        exchange.setProperty("llmFailureMessage", "failed");

        LlmJobDeadLetter deadLetter = deadLetters().failedJobDeadLetter(exchange);

        assertEquals("high-pass", deadLetter.laneId());
        assertEquals("failed", deadLetter.failureMessage());
    }

    @Test
    void deadLetterMetadataPreservesJsonSafeScalars() throws Exception {
        DefaultCamelContext context = TestCamelContexts.quiet();
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("string", "value");
        metadata.put("integer", 7);
        metadata.put("decimal", 2.5);
        metadata.put("boolean", true);
        LlmJob job = new LlmJob(
                UUID.randomUUID(),
                "low-pass",
                "inspect the code",
                "be concise",
                null,
                metadata,
                Instant.now());
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("llmJob", job);

        LlmJobDeadLetter deadLetter = deadLetters().failedJobDeadLetter(exchange);

        assertEquals("value", deadLetter.metadata().get("string"));
        assertEquals(7, deadLetter.metadata().get("integer"));
        assertEquals(2.5, deadLetter.metadata().get("decimal"));
        assertEquals(true, deadLetter.metadata().get("boolean"));
        String json = TestJson.write(deadLetter);
        assertTrue(json.contains("\"boolean\":true"));
    }

    @Test
    void deadLetterMetadataNormalizesNestedMapsAndListsRecursively() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        InferenceLaneDefinition lane = FrameworkFixtures.inferenceLaneRegistry().getRequired("low-pass");
        LinkedHashMap<String, Object> nested = new LinkedHashMap<>();
        nested.put("lane", lane);
        nested.put("items", List.of("ok", lane, Map.of("laneAgain", lane)));
        LlmJob job = job(UUID.randomUUID(), Map.of("nested", nested));
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("llmJob", job);

        LlmJobDeadLetter deadLetter = deadLetters().failedJobDeadLetter(exchange);

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
        LlmJob job = job(UUID.randomUUID(), Map.of("laneObject", lane));
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("llmJob", job);

        LlmJobDeadLetter deadLetter = deadLetters().failedJobDeadLetter(exchange);

        assertEquals("high-pass", deadLetter.metadata().get("laneObject"));
        String json = TestJson.write(deadLetter);
        assertFalse(json.contains("InferenceLaneDefinition"));
        assertFalse(json.contains("requestTopic"));
    }

    @Test
    void deadLetterMetadataConvertsKnownFrameworkObjectsToIds() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        LlmJob job = job(UUID.randomUUID(), Map.of(
                "laneObject", FrameworkFixtures.inferenceLaneRegistry().getRequired("high-pass"),
                "personaObject", FrameworkFixtures.personaCatalog().getRequired(FrameworkFixtures.PERSONA_A)));
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("llmJob", job);

        LlmJobDeadLetter deadLetter = deadLetters().failedJobDeadLetter(exchange);

        assertEquals("high-pass", deadLetter.metadata().get("laneObject"));
        assertEquals(FrameworkFixtures.PERSONA_A, deadLetter.metadata().get("personaObject"));
    }

    @Test
    void deadLetterMetadataUnknownObjectsUseBoundedClassQualifiedString() throws Exception {
        DefaultCamelContext context = TestCamelContexts.quiet();
        UnknownMetadataValue value = new UnknownMetadataValue("secret-ish-object-graph");
        LlmJob job = job(UUID.randomUUID(), Map.of("unknown", value));
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("llmJob", job);

        LlmJobDeadLetter deadLetter = deadLetters().failedJobDeadLetter(exchange);

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
        LlmJob job = job(UUID.randomUUID(), Map.of(
                "longString", longString,
                "longObject", new UnknownMetadataValue("x".repeat(2000))));
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("llmJob", job);

        LlmJobDeadLetter deadLetter = deadLetters().failedJobDeadLetter(exchange);

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
        LlmJob job = new LlmJob(
                jobId,
                "high-pass",
                "inspect the code",
                "be concise",
                null,
                Map.of(
                        "laneId", "high-pass",
                        "model", "selected-high-model",
                        "toolProfile", FrameworkFixtures.PROFILE_GAMMA),
                requestedAt);
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("llmJob", job);
        exchange.setProperty("modelId", "selected-high-model");
        exchange.setProperty("llmStartedAt", startedAt);
        exchange.setProperty(Exchange.EXCEPTION_CAUGHT, new IllegalStateException("agent failed", new RuntimeException("socket closed")));

        LlmJobResult result = deadLetters().failedLlmResult(exchange);

        assertEquals(jobId, result.jobId());
        assertEquals(LlmJobStatus.FAILED, result.status());
        assertEquals("high-pass", result.laneId());
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
        FrameworkFixtures.set(route, "routeSupport", routeSupport(context, registry, manager, deadLetters));
        FrameworkFixtures.set(route, "primitiveExecutor", FrameworkFixtures.primitiveExecutor());
        return route;
    }

    private static LlmRouteSupport routeSupport(DefaultCamelContext context) {
        return routeSupport(context, new InFlightReplyManager());
    }

    private static LlmRouteSupport routeSupport(DefaultCamelContext context, InFlightReplyManager manager) {
        return routeSupport(context, FrameworkFixtures.inferenceLaneRegistry(), manager, deadLetters());
    }

    private static LlmRouteSupport routeSupport(
            DefaultCamelContext context,
            InferenceLaneRegistry registry,
            InFlightReplyManager manager,
            LlmDeadLetterFactory deadLetters) {
        return new LlmRouteSupport(context, registry, manager, deadLetters);
    }

    private static LlmJobFactory jobFactory() {
        return jobFactory(FrameworkFixtures.inferenceLaneRegistry());
    }

    private static LlmJobFactory jobFactory(InferenceLaneRegistry registry) {
        return new LlmJobFactory(registry, FrameworkFixtures.personaCatalog());
    }

    private static LlmDeadLetterFactory deadLetters() {
        return new LlmDeadLetterFactory();
    }

    private static InferenceLaneRegistry registryWithModels(String lowLaneModel, String highLaneModel) {
        return new InferenceLaneRegistry(List.of(
                InferenceLaneDefinition.builder("low-pass")
                        .requestTopic("llm.requests.low")
                        .responseTopic("llm.replies.low")
                        .parallelism(1)
                        .endpoint("http://shared-openai.example/v1")
                        .model(lowLaneModel)
                        .build(),
                InferenceLaneDefinition.builder("high-pass")
                        .requestTopic("llm.requests.high")
                        .responseTopic("llm.replies.high")
                        .parallelism(1)
                        .endpoint("http://shared-openai.example/v1")
                        .model(highLaneModel)
                        .build()));
    }

    private static InferenceLaneRegistry registryWithLowLaneParallelism(int parallelism) {
        return new InferenceLaneRegistry(List.of(
                InferenceLaneDefinition.builder("low-pass")
                        .requestTopic("llm.requests.low")
                        .responseTopic("llm.replies.low")
                        .parallelism(parallelism)
                        .build()));
    }

    private static InferenceLaneRegistry registryWithPartitionStrategy(PartitionKeyStrategy strategy) {
        return new InferenceLaneRegistry(List.of(InferenceLaneDefinition.builder("low-pass")
                .requestTopic("llm.requests.low")
                .responseTopic("llm.replies.low")
                .partitionKeyStrategy(strategy)
                .build()));
    }

    private static Exchange exchangeForLane(DefaultCamelContext context, String laneId) {
        Exchange exchange = new DefaultExchange(context);
        UUID jobId = UUID.randomUUID();
        InferenceLaneDefinition lane = FrameworkFixtures.inferenceLaneRegistry().getRequired(laneId);
        exchange.setProperty("llmJobId", jobId);
        exchange.setProperty("laneId", laneId);
        exchange.setProperty("inferenceLane", lane);
        exchange.setProperty("llmJob", job(jobId, laneId, Map.of()));
        return exchange;
    }

    private static LlmJob job(UUID jobId) {
        return job(jobId, Map.of());
    }

    private static LlmJob job(UUID jobId, Map<String, Object> metadata) {
        return job(jobId, "low-pass", metadata);
    }

    private static LlmJob job(UUID jobId, String laneId, Map<String, Object> metadata) {
        return job(jobId, laneId, metadata, Instant.now());
    }

    private static LlmJob job(UUID jobId, String laneId, Map<String, Object> metadata, Instant requestedAt) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
        if (metadata != null) {
            merged.putAll(metadata);
        }
        if (laneId != null) {
            merged.putIfAbsent("laneId", laneId);
        }
        merged.putIfAbsent("primitive", "act");
        return new LlmJob(
                jobId,
                laneId,
                "inspect the code",
                "be concise",
                null,
                Map.copyOf(merged),
                requestedAt);
    }

    private record UnknownMetadataValue(String value) {
        @Override
        public String toString() {
            return "UnknownMetadataValue[value=" + value + "]";
        }
    }
}
