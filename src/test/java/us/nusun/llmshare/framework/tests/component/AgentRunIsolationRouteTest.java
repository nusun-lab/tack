package us.nusun.llmshare.framework.tests.component;

import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.inventory.RoutineCatalog;
import us.nusun.llmshare.framework.model.AgentRunHandle;
import us.nusun.llmshare.framework.model.AgentRunProjection;
import us.nusun.llmshare.framework.model.AgentRunRequest;
import us.nusun.llmshare.framework.model.AgentRunScheduled;
import us.nusun.llmshare.framework.model.Mission;
import us.nusun.llmshare.framework.projection.AgentRunProjectionManager;
import us.nusun.llmshare.framework.projection.ProjectionStore;
import us.nusun.llmshare.framework.state.AgentRun;
import us.nusun.llmshare.framework.state.AgentRunStore;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;
import us.nusun.llmshare.framework.tests.support.StubKafkaComponent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static us.nusun.llmshare.framework.tests.support.AgentRunRouteTestSupport.SCHEDULED_TOPIC;
import static us.nusun.llmshare.framework.tests.support.AgentRunRouteTestSupport.context;
import static us.nusun.llmshare.framework.tests.support.AgentRunRouteTestSupport.events;
import static us.nusun.llmshare.framework.tests.support.AgentRunRouteTestSupport.routine;
import static us.nusun.llmshare.framework.tests.support.AgentRunRouteTestSupport.scheduledEvent;
import static us.nusun.llmshare.framework.tests.support.AgentRunRouteTestSupport.waitForEvent;

class AgentRunIsolationRouteTest {
    @Test
    void startAgentRunReturnsHandleImmediatelyAndChildCompletesLater() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        AgentRunStore agentRuns = new AgentRunStore();
        StubKafkaComponent kafka = new StubKafkaComponent();
        RoutineCatalog routines = new RoutineCatalog(List.of(routine("test/parent", "direct:test-parent"), routine("test/child", "direct:slow-child")));
        FrameworkFixtures.set(manager, "routines", routines);
        CountDownLatch childStarted = new CountDownLatch(1);
        CountDownLatch childRelease = new CountDownLatch(1);

        try (DefaultCamelContext context = context(manager, routines, agentRuns, kafka, "agent-run-isolation-test")) {
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:slow-child")
                            .process(exchange -> childStarted.countDown())
                            .process(exchange -> childRelease.await(2, TimeUnit.SECONDS));
                }
            });
            context.start();
            ProducerTemplate template = context.createProducerTemplate();
            AgentRun parent = parentRun();
            Exchange exchange = preparedExchange(context, parent, new AgentRunRequest(FrameworkFixtures.PERSONA_A, "test/child", Map.of("repo", "parking")));

            long started = System.nanoTime();
            Exchange result = template.send("direct:start-agent-run", exchange);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            AgentRunHandle handle = result.getIn().getBody(AgentRunHandle.class);
            assertTrue(elapsedMs < 300, "start-agent-run should not wait for child routine completion");
            assertNotNull(handle);
            assertEquals("accepted", handle.status());
            assertEquals("mission-1", handle.missionId());
            assertEquals(parent.id(), handle.parentAgentRunId());
            assertEquals(FrameworkFixtures.PERSONA_A, handle.personaId());
            assertEquals("test/child", handle.routineId());
            assertNotEquals(parent.id(), handle.agentRunId());
            assertParentStateRestored(result, parent);

            assertEquals(1, agentRuns.missions().size());
            assertEquals(1, agentRuns.runs().size());
            assertEquals(List.of(SCHEDULED_TOPIC), kafka.sentTopics());
            AgentRunScheduled scheduled = scheduledEvent(kafka, 0);
            assertEquals(handle.missionId(), scheduled.missionId());
            assertEquals(handle.agentRunId(), scheduled.agentRunId());
            assertFalse(childStarted.await(100, TimeUnit.MILLISECONDS), "child routine should not start before scheduled event consumption");
            assertEquals(List.of("AGENT_RUN_ACCEPTED"), events(store.get(handle.agentRunId())));

            template.sendBody("direct:handle-agent-run-scheduled", scheduled);
            assertTrue(childStarted.await(2, TimeUnit.SECONDS));
            AgentRunProjection startedProjection = store.get(handle.agentRunId());
            assertNotNull(startedProjection);
            assertEquals(List.of("AGENT_RUN_ACCEPTED", "AGENT_RUN_STARTED"), events(startedProjection));

            childRelease.countDown();
            assertTrue(waitForEvent(store, handle.agentRunId(), "AGENT_RUN_COMPLETED"));
            AgentRunProjection completed = store.get(handle.agentRunId());
            assertEquals(parent.id(), completed.parentAgentRunId());
            assertEquals(List.of("AGENT_RUN_ACCEPTED", "AGENT_RUN_STARTED", "AGENT_RUN_COMPLETED"), events(completed));
        }
    }

    @Test
    void parentRoutineDoesNotWaitForScheduledChildCompletion() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        AgentRunStore agentRuns = new AgentRunStore();
        StubKafkaComponent kafka = new StubKafkaComponent();
        RoutineCatalog routines = new RoutineCatalog(List.of(routine("test/parent", "direct:parent-schedules-child"), routine("test/child", "direct:blocked-child")));
        FrameworkFixtures.set(manager, "routines", routines);
        CountDownLatch childStarted = new CountDownLatch(1);
        CountDownLatch childRelease = new CountDownLatch(1);

        try (DefaultCamelContext context = context(manager, routines, agentRuns, kafka, "agent-run-isolation-test")) {
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:parent-schedules-child")
                            .setBody(constant(new AgentRunRequest(FrameworkFixtures.PERSONA_A, "test/child", Map.of())))
                            .to("direct:start-agent-run")
                            .setProperty("parentContinuedAfterScheduling", constant(true));

                    from("direct:blocked-child")
                            .process(exchange -> childStarted.countDown())
                            .process(exchange -> childRelease.await(2, TimeUnit.SECONDS));
                }
            });
            context.start();
            ProducerTemplate template = context.createProducerTemplate();
            AgentRun parent = parentRun();
            Exchange exchange = preparedExchange(context, parent, null);

            long started = System.nanoTime();
            template.send("direct:parent-schedules-child", exchange);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue(elapsedMs < 300, "parent route should continue after scheduling child");
            assertEquals(true, exchange.getProperty("parentContinuedAfterScheduling"));
            assertParentStateRestored(exchange, parent);
            assertEquals(List.of(SCHEDULED_TOPIC), kafka.sentTopics());
            String childRunId = store.all().stream()
                    .filter(projection -> parent.id().equals(projection.parentAgentRunId()))
                    .findFirst()
                    .orElseThrow()
                    .agentRunId();
            template.sendBody("direct:handle-agent-run-scheduled", scheduledEvent(kafka, 0));
            assertTrue(childStarted.await(2, TimeUnit.SECONDS));
            childRelease.countDown();
            assertTrue(waitForEvent(store, childRunId, "AGENT_RUN_COMPLETED"));
        }
    }

    @Test
    void childFailureLaterUpdatesOnlyChildProjection() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        AgentRunStore agentRuns = new AgentRunStore();
        StubKafkaComponent kafka = new StubKafkaComponent();
        RoutineCatalog routines = new RoutineCatalog(List.of(routine("test/parent", "direct:test-parent"), routine("test/failing-child", "direct:failing-child")));
        FrameworkFixtures.set(manager, "routines", routines);

        try (DefaultCamelContext context = context(manager, routines, agentRuns, kafka, "agent-run-isolation-test")) {
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    errorHandler(noErrorHandler());
                    from("direct:failing-child").throwException(new IllegalStateException("child failed later"));
                }
            });
            context.start();
            AgentRun parent = parentRun();
            Exchange exchange = preparedExchange(context, parent, new AgentRunRequest(FrameworkFixtures.PERSONA_A, "test/failing-child", Map.of()));

            context.createProducerTemplate().send("direct:start-agent-run", exchange);
            AgentRunHandle handle = exchange.getIn().getBody(AgentRunHandle.class);

            assertParentStateRestored(exchange, parent);
            context.createProducerTemplate().sendBody("direct:handle-agent-run-scheduled", scheduledEvent(kafka, 0));
            assertTrue(waitForEvent(store, handle.agentRunId(), "AGENT_RUN_FAILED"));
            assertFalse(events(store.get(handle.agentRunId())).contains("AGENT_RUN_COMPLETED"));
            assertEquals("running", parent.status());
        }
    }


    @Test
    void startAgentRunsBatchCommitsAllChildrenBeforePublishingAnyScheduledEvent() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        AgentRunStore agentRuns = new AgentRunStore();
        AtomicInteger publishesSeen = new AtomicInteger();
        StubKafkaComponent kafka = new StubKafkaComponent((topic, exchange) -> {
            if (SCHEDULED_TOPIC.equals(topic)) {
                publishesSeen.incrementAndGet();
                assertEquals(2, agentRuns.runs().size(), "all child runs should be committed before first schedule publish");
            }
        });
        RoutineCatalog routines = new RoutineCatalog(List.of(
                routine("test/parent", "direct:test-parent"),
                routine("test/child-a", "direct:test-child-a"),
                routine("test/child-b", "direct:test-child-b")));
        FrameworkFixtures.set(manager, "routines", routines);

        try (DefaultCamelContext context = context(manager, routines, agentRuns, kafka, "agent-run-isolation-test")) {
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:test-child-a").setBody(constant("ok-a"));
                    from("direct:test-child-b").setBody(constant("ok-b"));
                }
            });
            context.start();
            AgentRun parent = parentRun();
            Exchange exchange = preparedExchange(context, parent, List.of(
                    new AgentRunRequest(FrameworkFixtures.PERSONA_A, "test/child-a", Map.of()),
                    new AgentRunRequest(FrameworkFixtures.PERSONA_A, "test/child-b", Map.of())));

            context.createProducerTemplate().send("direct:start-agent-runs", exchange);

            @SuppressWarnings("unchecked")
            List<AgentRunHandle> handles = exchange.getIn().getBody(List.class);
            assertEquals(2, handles.size());
            assertEquals(2, agentRuns.runs().size());
            assertEquals(2, kafka.sentTo(SCHEDULED_TOPIC).size());
            assertEquals(2, publishesSeen.get());
            assertParentStateRestored(exchange, parent);
        }
    }

    @Test
    void startAgentRunsBatchAcceptsEmptyPlanningResultAsNoOp() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        AgentRunStore agentRuns = new AgentRunStore();
        StubKafkaComponent kafka = new StubKafkaComponent();
        RoutineCatalog routines = new RoutineCatalog(List.of(routine("test/parent", "direct:test-parent")));
        FrameworkFixtures.set(manager, "routines", routines);

        try (DefaultCamelContext context = context(manager, routines, agentRuns, kafka, "agent-run-isolation-test")) {
            context.start();
            AgentRun parent = parentRun();
            Exchange exchange = preparedExchange(context, parent, List.of());

            Exchange result = context.createProducerTemplate().send("direct:start-agent-runs", exchange);

            assertNull(result.getException());
            @SuppressWarnings("unchecked")
            List<AgentRunHandle> handles = result.getIn().getBody(List.class);
            assertEquals(List.of(), handles);
            assertEquals(1, agentRuns.missions().size());
            assertEquals(0, agentRuns.runs().size());
            assertEquals(0, store.all().size());
            assertEquals(List.of(), kafka.sentTopics());
            assertParentStateRestored(exchange, parent);
        }
    }

    @Test
    void schedulingValidationFailureRestoresParentState() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        AgentRunStore agentRuns = new AgentRunStore();
        StubKafkaComponent kafka = new StubKafkaComponent();
        RoutineCatalog routines = new RoutineCatalog(List.of(routine("test/parent", "direct:test-parent"), routine("test/child", "direct:test-child")));
        FrameworkFixtures.set(manager, "routines", routines);

        try (DefaultCamelContext context = context(manager, routines, agentRuns, kafka, "agent-run-isolation-test")) {
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:test-child").setBody(constant("should not run"));
                }
            });
            context.start();
            AgentRun parent = parentRun();
            Exchange exchange = preparedExchange(context, parent, new AgentRunRequest(FrameworkFixtures.PERSONA_A, "test/child", Map.of()));
            exchange.getIn().setBody(Map.of("personaId", "missing", "routineId", "test/child"));

            Exchange result = context.createProducerTemplate().send("direct:start-agent-run", exchange);
            assertNotNull(result.getException());
            assertParentStateRestored(exchange, parent);
            assertEquals(0, agentRuns.missions().size());
            assertEquals(0, agentRuns.runs().size());
            assertEquals(0, store.all().size());
            assertEquals(List.of(), kafka.sentTopics());

            Exchange missingRoutine = preparedExchange(context, parent, new AgentRunRequest(FrameworkFixtures.PERSONA_A, "test/child", Map.of()));
            missingRoutine.getIn().setBody(Map.of("personaId", FrameworkFixtures.PERSONA_A, "routineId", "missing"));
            Exchange missingRoutineResult = context.createProducerTemplate().send("direct:start-agent-run", missingRoutine);
            assertNotNull(missingRoutineResult.getException());
            assertParentStateRestored(missingRoutine, parent);
            assertEquals(0, agentRuns.missions().size());
            assertEquals(0, agentRuns.runs().size());
            assertEquals(0, store.all().size());
            assertEquals(List.of(), kafka.sentTopics());
        }
    }

    private static Exchange preparedExchange(DefaultCamelContext context, AgentRun parent, Object request) {
        Exchange exchange = new DefaultExchange(context);
        Mission mission = new Mission("mission-1", "Build", List.of(), "active", Map.of(), Instant.now());
        exchange.setProperty("mission", mission);
        exchange.setProperty("agentRun", parent);
        exchange.setProperty("projectionTimeline", new Object());
        if (request != null) {
            exchange.getIn().setBody(request);
        }
        return exchange;
    }

    private static AgentRun parentRun() {
        return new AgentRun("run-parent", "mission-1", FrameworkFixtures.PERSONA_A, "test/parent", Map.of());
    }

    private static void assertParentStateRestored(Exchange exchange, AgentRun parent) {
        assertSame(parent, exchange.getProperty("agentRun"));
        assertEquals("mission-1", exchange.getProperty("mission", Mission.class).id());
    }

}
