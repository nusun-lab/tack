package us.nusun.llmshare.framework.tests.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.llm.InFlightReplyManager;
import us.nusun.llmshare.framework.llm.LlmDeadLetterFactory;
import us.nusun.llmshare.framework.llm.LlmJobFactory;
import us.nusun.llmshare.framework.llm.LlmRouteSupport;
import us.nusun.llmshare.framework.inventory.ExecutionRuntimeRegistry;
import us.nusun.llmshare.framework.model.ExecutionRuntimeDefinition;
import us.nusun.llmshare.framework.model.InferenceLaneDefinition;
import us.nusun.llmshare.framework.model.LlmJobDeadLetter;
import us.nusun.llmshare.framework.model.LlmJobEnvelope;
import us.nusun.llmshare.framework.model.LlmJobRequest;
import us.nusun.llmshare.framework.model.LlmJobResult;
import us.nusun.llmshare.framework.model.LlmJobStatus;
import us.nusun.llmshare.framework.model.Mission;
import us.nusun.llmshare.framework.model.PartitionKeyStrategy;
import us.nusun.llmshare.framework.route.LlmRoute;
import us.nusun.llmshare.framework.state.AgentRun;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;
import us.nusun.llmshare.framework.tests.support.TestCamelContexts;

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
            assertTrue(failure.getMessage().contains("runtimeId=tool-high"));
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
    void laneParallelismDrivesKafkaWorkerConsumerCount() throws Exception {
        DefaultCamelContext context = TestCamelContexts.quiet();
        LlmRoute route = route(context, new ExecutionRuntimeRegistry(
                List.of(InferenceLaneDefinition.builder("low-pass")
                        .requestTopic("llm.requests.low")
                        .responseTopic("llm.replies.low")
                        .parallelism(3)
                        .build()),
                List.of(ExecutionRuntimeDefinition.builder("tool-low")
                        .lane("low-pass")
                        .toolCalling(true)
                        .agentFactoryBeanName("executionRuntimeAgentFactory")
                        .build())));

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
        UUID jobId = UUID.randomUUID();
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("llmJobId", jobId);
        exchange.setProperty("personaId", "persona-a");
        exchange.setProperty("toolProfile", "source-control");
        exchange.getIn().setHeader("executionRuntime", "tool-high");
        exchange.getIn().setBody("inspect high lane");

        jobFactory().prepare(exchange);

        LlmJobEnvelope envelope = exchange.getIn().getBody(LlmJobEnvelope.class);
        assertEquals(jobId, envelope.jobId());
        assertEquals(LlmJobStatus.QUEUED, envelope.status());
        assertEquals("tool-high", envelope.requester());
        assertEquals("high-pass", exchange.getProperty("inferenceLane", InferenceLaneDefinition.class).id());
        assertEquals("llm.requests.high", exchange.getProperty("laneRequestTopic"));
        assertEquals("llm.replies.high", exchange.getProperty("laneReplyTopic"));
        assertEquals(jobId.toString(), exchange.getProperty("llmPartitionKey"));
        assertEquals("tool-high", envelope.metadata().get("executionRuntimeId"));
        assertEquals("high-pass", envelope.metadata().get("inferenceLane"));
        assertEquals("AGENT_RUN", envelope.metadata().get("partitionKeyStrategy"));
        assertEquals("persona-a", envelope.metadata().get("personaId"));
        assertEquals("source-control", envelope.metadata().get("toolProfile"));
    }

    @Test
    void defaultAgentRunPartitionKeyUsesAgentRunThenMissionThenJob() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        UUID jobId = UUID.randomUUID();
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("agentRun", new AgentRun("run-1", "mission-1", "persona-a", "routine-a", Map.of()));
        exchange.setProperty("mission", new Mission("mission-1", "goal", List.of(), "active", Map.of(), Instant.now()));
        exchange.setProperty("llmJobId", jobId);
        exchange.getIn().setHeader("executionRuntime", "tool-low");
        exchange.getIn().setBody("work");

        jobFactory().prepare(exchange);
        assertEquals("run-1", exchange.getProperty("llmPartitionKey"));

        Exchange missionFallback = new DefaultExchange(context);
        missionFallback.setProperty("mission", new Mission("mission-2", "goal", List.of(), "active", Map.of(), Instant.now()));
        missionFallback.setProperty("llmJobId", jobId);
        missionFallback.getIn().setHeader("executionRuntime", "tool-low");
        missionFallback.getIn().setBody("work");
        jobFactory().prepare(missionFallback);
        assertEquals("mission-2", missionFallback.getProperty("llmPartitionKey"));

        Exchange jobFallback = new DefaultExchange(context);
        jobFallback.setProperty("llmJobId", jobId);
        jobFallback.getIn().setHeader("executionRuntime", "tool-low");
        jobFallback.getIn().setBody("work");
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
        exchange.setProperty("llmJobId", jobId);
        exchange.getIn().setHeader("executionRuntime", "tool-low");
        exchange.getIn().setBody("work");

        jobs.prepare(exchange);
        assertEquals("mission-1", exchange.getProperty("llmPartitionKey"));

        Exchange runFallback = new DefaultExchange(context);
        runFallback.setProperty("agentRun", new AgentRun("run-2", "mission-2", "persona-a", "routine-a", Map.of()));
        runFallback.setProperty("llmJobId", jobId);
        runFallback.getIn().setHeader("executionRuntime", "tool-low");
        runFallback.getIn().setBody("work");
        jobs.prepare(runFallback);
        assertEquals("run-2", runFallback.getProperty("llmPartitionKey"));

        Exchange jobFallback = new DefaultExchange(context);
        jobFallback.setProperty("llmJobId", jobId);
        jobFallback.getIn().setHeader("executionRuntime", "tool-low");
        jobFallback.getIn().setBody("work");
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
        exchange.setProperty("llmJobId", jobId);
        exchange.getIn().setHeader("executionRuntime", "tool-low");
        exchange.getIn().setBody("work");

        jobs.prepare(exchange);

        assertEquals(jobId.toString(), exchange.getProperty("llmPartitionKey"));
    }

    @Test
    void kafkaKeyHelpersUsePartitionKeyForRequestsAndJobIdForReplies() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        UUID jobId = UUID.randomUUID();
        Exchange exchange = new DefaultExchange(context);
        LlmJobEnvelope job = queuedJob(jobId);
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
        LlmJobFactory jobs = jobFactory(registryWithModels("", "low-lane-model", "", "high-lane-model"));
        Exchange low = new DefaultExchange(context);
        low.getIn().setHeader("executionRuntime", "tool-low");
        low.getIn().setBody("low work");
        Exchange high = new DefaultExchange(context);
        high.getIn().setHeader("executionRuntime", "tool-high");
        high.getIn().setBody("high work");

        jobs.prepare(low);
        jobs.prepare(high);

        assertEquals("low-lane-model", low.getProperty("modelId"));
        assertEquals("high-lane-model", high.getProperty("modelId"));
        assertEquals("low-lane-model", low.getIn().getBody(LlmJobEnvelope.class).metadata().get("model"));
        assertEquals("high-lane-model", high.getIn().getBody(LlmJobEnvelope.class).metadata().get("model"));
    }

    @Test
    void runtimeModelOverrideWinsOverLaneModelAndGlobalFallback() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        Properties properties = new Properties();
        properties.setProperty("llm.runtime-default.openai.model", "global-model");
        context.getPropertiesComponent().setOverrideProperties(properties);
        LlmJobFactory jobs = jobFactory(registryWithModels("runtime-low-model", "low-lane-model", "", "high-lane-model"));
        Exchange exchange = new DefaultExchange(context);
        exchange.getIn().setHeader("executionRuntime", "tool-low");
        exchange.getIn().setBody("runtime override work");

        jobs.prepare(exchange);

        assertEquals("runtime-low-model", exchange.getProperty("modelId"));
        assertEquals("runtime-low-model", exchange.getIn().getBody(LlmJobEnvelope.class).metadata().get("model"));
    }

    @Test
    void globalModelRemainsFallbackWhenRuntimeAndLaneDoNotSelectModel() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        Properties properties = new Properties();
        properties.setProperty("llm.runtime-default.openai.model", "global-model");
        context.getPropertiesComponent().setOverrideProperties(properties);
        LlmJobFactory jobs = jobFactory(registryWithModels("", "", "", ""));
        Exchange exchange = new DefaultExchange(context);
        exchange.getIn().setHeader("executionRuntime", "tool-low");
        exchange.getIn().setBody("fallback work");

        jobs.prepare(exchange);

        assertEquals("global-model", exchange.getProperty("modelId"));
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
        Exchange exchange = exchangeForLane(context, "low-pass");
        exchange.setProperty("llmJobId", jobId);
        exchange.setProperty("llmJob", job);

        LlmRouteSupport.LlmAvailabilityException failure = assertThrows(
                LlmRouteSupport.LlmAvailabilityException.class,
                () -> support.awaitReply(jobId, exchange));

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
        UUID jobId = UUID.randomUUID();
        LlmJobEnvelope job = queuedJob(jobId);
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("llmJob", job);
        exchange.setProperty("llmJobId", jobId);
        exchange.setProperty("llmFailureMessage", "schema validation failed");

        LlmJobDeadLetter deadLetter = deadLetters().failedQueuedJobDeadLetter(exchange);

        assertEquals(jobId, deadLetter.jobId());
        assertEquals("low-pass", deadLetter.laneId());
        assertEquals("tool-low", deadLetter.runtimeId());
        assertEquals("tool-low", deadLetter.requester());
        assertEquals("qwen-36-27b", deadLetter.model());
        assertEquals("act", deadLetter.metadata().get("primitive"));
        assertEquals("schema validation failed", deadLetter.failureMessage());
        assertEquals(job.requestedAt(), deadLetter.requestedAt());
        assertTrue(deadLetter.timestamp().isAfter(Instant.EPOCH));

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(deadLetter);
        assertTrue(json.contains("\"jobId\""));
        assertTrue(json.contains("\"failureMessage\":\"schema validation failed\""));
        assertTrue(json.contains("\"primitive\":\"act\""));
    }

    @Test
    void deadLetterPrefersTypedLaneDefinitionOverMetadata() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        LlmJobEnvelope job = queuedJob(UUID.randomUUID(), Map.of(
                "executionRuntimeId", "tool-low",
                "inferenceLane", "metadata-lane"));
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("llmJob", job);
        exchange.setProperty("inferenceLane", FrameworkFixtures.executionRuntimeRegistry().laneRequired("high-pass"));

        LlmJobDeadLetter deadLetter = deadLetters().failedQueuedJobDeadLetter(exchange);

        assertEquals("high-pass", deadLetter.laneId());
        assertEquals("tool-low", deadLetter.runtimeId());
    }

    @Test
    void deadLetterAcceptsStringLaneProperty() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        LlmJobEnvelope job = queuedJob(UUID.randomUUID(), Map.of(
                "executionRuntimeId", "synthesis-low",
                "inferenceLane", "metadata-lane"));
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("llmJob", job);
        exchange.setProperty("inferenceLane", "string-lane");

        LlmJobDeadLetter deadLetter = deadLetters().failedQueuedJobDeadLetter(exchange);

        assertEquals("string-lane", deadLetter.laneId());
        assertEquals("synthesis-low", deadLetter.runtimeId());
    }

    @Test
    void deadLetterFallsBackToJobMetadataWhenLanePropertyIsMissing() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        LlmJobEnvelope job = queuedJob(UUID.randomUUID(), Map.of(
                "executionRuntimeId", "synthesis-high",
                "inferenceLane", "metadata-lane",
                "model", "metadata-model"));
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("llmJob", job);
        exchange.setProperty("llmFailureMessage", "failed");

        LlmJobDeadLetter deadLetter = deadLetters().failedQueuedJobDeadLetter(exchange);

        assertEquals("metadata-lane", deadLetter.laneId());
        assertEquals("synthesis-high", deadLetter.runtimeId());
        assertEquals("failed", deadLetter.failureMessage());
    }

    @Test
    void deadLetterUsesUnknownFallbacksWithoutObjectToStringLaneIds() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        UUID jobId = UUID.randomUUID();
        LlmJobEnvelope job = queuedJob(jobId, Map.of("inferenceLane", FrameworkFixtures.executionRuntimeRegistry().laneRequired("low-pass")));
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("llmJob", job);

        LlmJobDeadLetter deadLetter = deadLetters().failedQueuedJobDeadLetter(exchange);

        assertEquals("unknown", deadLetter.laneId());
        assertEquals("tool-low", deadLetter.runtimeId());
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
                "tool-low",
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
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(deadLetter);
        assertTrue(json.contains("\"nullValue\":null"));
    }

    @Test
    void deadLetterMetadataNormalizesNestedMapsAndListsRecursively() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        InferenceLaneDefinition lane = FrameworkFixtures.executionRuntimeRegistry().laneRequired("low-pass");
        LinkedHashMap<String, Object> nested = new LinkedHashMap<>();
        nested.put("lane", lane);
        nested.put("items", List.of("ok", lane, Map.of("runtime", FrameworkFixtures.executionRuntimeRegistry().getRequired("tool-low"))));
        LlmJobEnvelope job = queuedJob(UUID.randomUUID(), Map.of("nested", nested));
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("llmJob", job);

        LlmJobDeadLetter deadLetter = deadLetters().failedQueuedJobDeadLetter(exchange);

        Map<?, ?> normalizedNested = (Map<?, ?>) deadLetter.metadata().get("nested");
        assertEquals("low-pass", normalizedNested.get("lane"));
        List<?> items = (List<?>) normalizedNested.get("items");
        assertEquals("ok", items.get(0));
        assertEquals("low-pass", items.get(1));
        assertEquals("tool-low", ((Map<?, ?>) items.get(2)).get("runtime"));
    }

    @Test
    void deadLetterMetadataConvertsInferenceLaneDefinitionToLaneId() throws Exception {
        DefaultCamelContext context = TestCamelContexts.quiet();
        InferenceLaneDefinition lane = FrameworkFixtures.executionRuntimeRegistry().laneRequired("high-pass");
        LlmJobEnvelope job = queuedJob(UUID.randomUUID(), Map.of("laneObject", lane));
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("llmJob", job);

        LlmJobDeadLetter deadLetter = deadLetters().failedQueuedJobDeadLetter(exchange);

        assertEquals("high-pass", deadLetter.metadata().get("laneObject"));
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(deadLetter);
        assertFalse(json.contains("InferenceLaneDefinition"));
        assertFalse(json.contains("requestTopic"));
    }

    @Test
    void deadLetterMetadataConvertsKnownFrameworkObjectsToIds() {
        DefaultCamelContext context = TestCamelContexts.quiet();
        LlmJobEnvelope job = queuedJob(UUID.randomUUID(), Map.of(
                "runtimeObject", FrameworkFixtures.executionRuntimeRegistry().getRequired("synthesis-high"),
                "personaObject", FrameworkFixtures.personaCatalog().getRequired(FrameworkFixtures.PERSONA_A)));
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("llmJob", job);

        LlmJobDeadLetter deadLetter = deadLetters().failedQueuedJobDeadLetter(exchange);

        assertEquals("synthesis-high", deadLetter.metadata().get("runtimeObject"));
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
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(deadLetter);
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
        properties.setProperty("llm.runtime-default.openai.model", "global-model");
        context.getPropertiesComponent().setOverrideProperties(properties);
        Instant requestedAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant startedAt = Instant.parse("2026-01-01T00:00:05Z");
        UUID jobId = UUID.randomUUID();
        LlmJobEnvelope job = LlmJobEnvelope.queued(new LlmJobRequest(
                jobId,
                "tool-high",
                "developer",
                0,
                "inspect the code",
                "be concise",
                null,
                Map.of(
                        "executionRuntimeId", "tool-high",
                        "inferenceLane", "high-pass",
                        "model", "selected-high-model",
                        "toolProfile", "source-control"),
                requestedAt));
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty("llmJob", job);
        exchange.setProperty("llmStartedAt", startedAt);
        exchange.setProperty(Exchange.EXCEPTION_CAUGHT, new IllegalStateException("agent failed", new RuntimeException("socket closed")));

        LlmJobResult result = deadLetters().failedLlmResult(exchange);

        assertEquals(jobId, result.jobId());
        assertEquals(LlmJobStatus.FAILED, result.status());
        assertEquals("tool-high", result.requester());
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
        return route(context, manager, FrameworkFixtures.executionRuntimeRegistry());
    }

    private static LlmRoute route(DefaultCamelContext context, ExecutionRuntimeRegistry registry) {
        return route(context, new InFlightReplyManager(), registry);
    }

    private static LlmRoute route(DefaultCamelContext context, InFlightReplyManager manager, ExecutionRuntimeRegistry registry) {
        LlmDeadLetterFactory deadLetters = deadLetters();
        LlmRoute route = new LlmRoute();
        FrameworkFixtures.set(route, "runtimes", registry);
        FrameworkFixtures.set(route, "jobFactory", jobFactory(registry));
        FrameworkFixtures.set(route, "deadLetterFactory", deadLetters);
        FrameworkFixtures.set(route, "routeSupport", routeSupport(context, registry, manager, deadLetters));
        return route;
    }

    private static LlmRouteSupport routeSupport(DefaultCamelContext context) {
        return routeSupport(context, new InFlightReplyManager());
    }

    private static LlmRouteSupport routeSupport(DefaultCamelContext context, InFlightReplyManager manager) {
        return routeSupport(context, FrameworkFixtures.executionRuntimeRegistry(), manager, deadLetters());
    }

    private static LlmRouteSupport routeSupport(
            DefaultCamelContext context,
            ExecutionRuntimeRegistry registry,
            InFlightReplyManager manager,
            LlmDeadLetterFactory deadLetters) {
        return new LlmRouteSupport(context, registry, manager, deadLetters);
    }

    private static LlmJobFactory jobFactory() {
        return jobFactory(FrameworkFixtures.executionRuntimeRegistry());
    }

    private static LlmJobFactory jobFactory(ExecutionRuntimeRegistry registry) {
        return new LlmJobFactory(registry, FrameworkFixtures.personaCatalog());
    }

    private static LlmDeadLetterFactory deadLetters() {
        return new LlmDeadLetterFactory();
    }

    private static ExecutionRuntimeRegistry registryWithModels(String lowRuntimeModel, String lowLaneModel, String highRuntimeModel, String highLaneModel) {
        return new ExecutionRuntimeRegistry(
                List.of(
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
                                .build()),
                List.of(
                        ExecutionRuntimeDefinition.builder("tool-low")
                                .lane("low-pass")
                                .toolCalling(true)
                                .model(lowRuntimeModel)
                                .agentFactoryBeanName("executionRuntimeAgentFactory")
                                .build(),
                        ExecutionRuntimeDefinition.builder("tool-high")
                                .lane("high-pass")
                                .toolCalling(true)
                                .model(highRuntimeModel)
                                .agentFactoryBeanName("executionRuntimeAgentFactory")
                                .build()));
    }

    private static ExecutionRuntimeRegistry registryWithPartitionStrategy(PartitionKeyStrategy strategy) {
        return new ExecutionRuntimeRegistry(
                List.of(InferenceLaneDefinition.builder("low-pass")
                        .requestTopic("llm.requests.low")
                        .responseTopic("llm.replies.low")
                        .partitionKeyStrategy(strategy)
                        .build()),
                List.of(ExecutionRuntimeDefinition.builder("tool-low")
                        .lane("low-pass")
                        .toolCalling(true)
                        .agentFactoryBeanName("executionRuntimeAgentFactory")
                        .build()));
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
        return queuedJob(jobId, Map.of(
                "executionRuntimeId", "tool-low",
                "inferenceLane", "low-pass",
                "primitive", "act"));
    }

    private static LlmJobEnvelope queuedJob(UUID jobId, Map<String, Object> metadata) {
        return queuedJob(jobId, metadata, Instant.now());
    }

    private static LlmJobEnvelope queuedJob(UUID jobId, Map<String, Object> metadata, Instant requestedAt) {
        return LlmJobEnvelope.queued(new LlmJobRequest(
                jobId,
                "tool-low",
                "developer",
                0,
                "inspect the code",
                "be concise",
                null,
                metadata,
                requestedAt));
    }

    private record UnknownMetadataValue(String value) {
        @Override
        public String toString() {
            return "UnknownMetadataValue[value=" + value + "]";
        }
    }
}
