package us.nusun.tack.framework.tests.component;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;
import us.nusun.tack.framework.inventory.RoutineCatalog;
import us.nusun.tack.framework.model.AgentRunHandle;
import us.nusun.tack.framework.model.AgentRunRequest;
import us.nusun.tack.framework.model.AgentRunScheduled;
import us.nusun.tack.framework.model.MissionRequest;
import us.nusun.tack.framework.projection.AgentRunProjectionManager;
import us.nusun.tack.framework.projection.ProjectionStore;
import us.nusun.tack.framework.state.AgentRunStore;
import us.nusun.tack.framework.tests.support.FrameworkFixtures;
import us.nusun.tack.framework.tests.support.StubKafkaComponent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static us.nusun.tack.framework.tests.support.AgentRunRouteTestSupport.SCHEDULED_TOPIC;
import static us.nusun.tack.framework.tests.support.AgentRunRouteTestSupport.context;
import static us.nusun.tack.framework.tests.support.AgentRunRouteTestSupport.events;
import static us.nusun.tack.framework.tests.support.AgentRunRouteTestSupport.noopRoute;
import static us.nusun.tack.framework.tests.support.AgentRunRouteTestSupport.routine;
import static us.nusun.tack.framework.tests.support.AgentRunRouteTestSupport.routineWithSubroutine;
import static us.nusun.tack.framework.tests.support.AgentRunRouteTestSupport.scheduledEvent;
import static us.nusun.tack.framework.tests.support.AgentRunRouteTestSupport.waitForEvent;

class MissionRouteAsyncTest {
    @Test
    void startMissionAcceptsOneRunWithoutWaitingForLongRoutine() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        AgentRunStore agentRuns = new AgentRunStore();
        StubKafkaComponent kafka = new StubKafkaComponent();
        RoutineCatalog routines = new RoutineCatalog(List.of(routine(FrameworkFixtures.ROUTINE_A, "direct:slow-root-routine")));
        FrameworkFixtures.set(manager, "routines", routines);
        CountDownLatch routineCompleted = new CountDownLatch(1);

        try (DefaultCamelContext context = context(manager, routines, agentRuns, kafka, "mission-route-test")) {
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:slow-root-routine")
                            .delay(500)
                            .process(exchange -> routineCompleted.countDown());
                }
            });
            context.start();

            long started = System.nanoTime();
            @SuppressWarnings("unchecked")
            Map<String, Object> response = context.createProducerTemplate().requestBodyAndHeader(
                    "direct:start-mission",
                    new MissionRequest("Build a parking app", List.of(new AgentRunRequest(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A, Map.of()))),
                    "requestId",
                    "mission-async",
                    Map.class);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue(elapsedMs < 300, "mission acceptance should not wait for the 500ms routine");
            assertEquals("mission-async", response.get("missionId"));
            assertEquals("accepted", response.get("status"));
            AgentRunHandle handle = firstHandle(response);
            assertEquals("accepted", handle.status());
            assertEquals("mission-async", handle.missionId());
            assertEquals(1, agentRuns.missions().size());
            assertEquals(1, agentRuns.runs().size());
            assertNotNull(store.get(handle.agentRunId()));
            assertEquals(List.of("AGENT_RUN_ACCEPTED"), events(store.get(handle.agentRunId())));
            assertEquals(List.of(SCHEDULED_TOPIC), kafka.sentTopics());
            AgentRunScheduled scheduled = scheduledEvent(kafka, 0);
            assertEquals(handle.missionId(), scheduled.missionId());
            assertEquals(handle.agentRunId(), scheduled.agentRunId());
            assertFalse(routineCompleted.await(100, TimeUnit.MILLISECONDS), "routine should not start until AgentRunScheduled is consumed");

            context.createProducerTemplate().sendBody("direct:handle-agent-run-scheduled", scheduled);
            assertTrue(routineCompleted.await(2, TimeUnit.SECONDS));
            assertTrue(waitForEvent(store, handle.agentRunId(), "AGENT_RUN_COMPLETED"));
            assertEquals(List.of("AGENT_RUN_ACCEPTED", "AGENT_RUN_STARTED", "AGENT_RUN_COMPLETED"), events(store.get(handle.agentRunId())));
        }
    }

    @Test
    void startMissionAcceptsMultipleTopLevelSiblingRuns() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        AgentRunStore agentRuns = new AgentRunStore();
        StubKafkaComponent kafka = new StubKafkaComponent();
        RoutineCatalog routines = new RoutineCatalog(List.of(
                routine(FrameworkFixtures.ROUTINE_A, "direct:routine-a"),
                routine(FrameworkFixtures.ROUTINE_B, "direct:routine-b")));
        FrameworkFixtures.set(manager, "routines", routines);

        try (DefaultCamelContext context = context(manager, routines, agentRuns, kafka, "mission-route-test")) {
            context.addRoutes(noopRoute("direct:routine-a"));
            context.addRoutes(noopRoute("direct:routine-b"));
            context.start();

            @SuppressWarnings("unchecked")
            Map<String, Object> response = context.createProducerTemplate().requestBody(
                    "direct:start-mission",
                    new MissionRequest("Build", List.of(
                            new AgentRunRequest(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A, Map.of()),
                            new AgentRunRequest(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_B, Map.of("repo", "parking")))),
                    Map.class);

            @SuppressWarnings("unchecked")
            List<AgentRunHandle> handles = (List<AgentRunHandle>) response.get("agentRuns");
            assertEquals(2, handles.size());
            assertNotEquals(handles.get(0).agentRunId(), handles.get(1).agentRunId());
            assertEquals(response.get("missionId"), handles.get(0).missionId());
            assertEquals(response.get("missionId"), handles.get(1).missionId());
            assertEquals(null, handles.get(0).parentAgentRunId());
            assertEquals(null, handles.get(1).parentAgentRunId());
            assertEquals(1, agentRuns.missions().size());
            assertEquals(2, agentRuns.runs().size());
            assertEquals(2, store.all().size());
            assertEquals(List.of("AGENT_RUN_ACCEPTED"), events(store.get(handles.get(0).agentRunId())));
            assertEquals(List.of("AGENT_RUN_ACCEPTED"), events(store.get(handles.get(1).agentRunId())));
            assertEquals(List.of(SCHEDULED_TOPIC, SCHEDULED_TOPIC), kafka.sentTopics());
            assertEquals(handles.get(0).agentRunId(), scheduledEvent(kafka, 0).agentRunId());
            assertEquals(handles.get(1).agentRunId(), scheduledEvent(kafka, 1).agentRunId());

            context.createProducerTemplate().sendBody("direct:handle-agent-run-scheduled", scheduledEvent(kafka, 0));
            context.createProducerTemplate().sendBody("direct:handle-agent-run-scheduled", scheduledEvent(kafka, 1));
            assertTrue(waitForEvent(store, handles.get(0).agentRunId(), "AGENT_RUN_COMPLETED"));
            assertTrue(waitForEvent(store, handles.get(1).agentRunId(), "AGENT_RUN_COMPLETED"));
        }
    }


    @Test
    void subroutineTargetedMissionRunDispatchesToDeclaredSubroutineRouteAndProjectsContext() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        AgentRunStore agentRuns = new AgentRunStore();
        StubKafkaComponent kafka = new StubKafkaComponent();
        RoutineCatalog routines = new RoutineCatalog(List.of(
                routineWithSubroutine(FrameworkFixtures.ROUTINE_A, "direct:full-routine", "phase-a", "direct:phase-a")));
        FrameworkFixtures.set(manager, "routines", routines);
        AtomicInteger fullStarts = new AtomicInteger();
        AtomicInteger phaseStarts = new AtomicInteger();

        try (DefaultCamelContext context = context(manager, routines, agentRuns, kafka, "mission-route-test")) {
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:full-routine").process(exchange -> fullStarts.incrementAndGet());
                    from("direct:phase-a").process(exchange -> {
                        phaseStarts.incrementAndGet();
                        exchange.setProperty("phaseInput", exchange.getIn().getBody());
                    });
                }
            });
            context.start();

            @SuppressWarnings("unchecked")
            Map<String, Object> response = context.createProducerTemplate().requestBody(
                    "direct:start-mission",
                    new MissionRequest("Build", List.of(new AgentRunRequest(
                            FrameworkFixtures.PERSONA_A,
                            FrameworkFixtures.ROUTINE_A,
                            "phase-a",
                            Map.of("focus", "Validate phase A behavior.")))),
                    Map.class);

            AgentRunHandle handle = firstHandle(response);
            assertEquals("phase-a", handle.subroutineId());
            assertEquals("phase-a", agentRuns.getRun(handle.agentRunId()).subroutineId());
            assertEquals("phase-a", store.get(handle.agentRunId()).subroutine().get("id"));
            assertEquals("Run phase-a", store.get(handle.agentRunId()).subroutine().get("description"));
            assertEquals("Validate phase A behavior.", ((Map<?, ?>) store.get(handle.agentRunId()).input()).get("focus"));

            context.createProducerTemplate().sendBody("direct:handle-agent-run-scheduled", scheduledEvent(kafka, 0));
            assertTrue(waitForEvent(store, handle.agentRunId(), "AGENT_RUN_COMPLETED"));
            assertEquals(0, fullStarts.get());
            assertEquals(1, phaseStarts.get());
        }
    }

    @Test
    void unknownOrCrossRoutineSubroutineFailsBeforeAnyMissionSideEffects() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        AgentRunStore agentRuns = new AgentRunStore();
        StubKafkaComponent kafka = new StubKafkaComponent();
        RoutineCatalog routines = new RoutineCatalog(List.of(
                routineWithSubroutine(FrameworkFixtures.ROUTINE_A, "direct:routine-a", "phase-a", "direct:phase-a"),
                routineWithSubroutine(FrameworkFixtures.ROUTINE_B, "direct:routine-b", "phase-b", "direct:phase-b")));
        FrameworkFixtures.set(manager, "routines", routines);

        try (DefaultCamelContext context = context(manager, routines, agentRuns, kafka, "mission-route-test")) {
            context.start();

            assertMissionFailsBeforeSideEffects(context, agentRuns, store, kafka,
                    new MissionRequest("Build", List.of(new AgentRunRequest(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A, "missing", Map.of()))),
                    "Unknown subroutine missing for routine " + FrameworkFixtures.ROUTINE_A);
            assertMissionFailsBeforeSideEffects(context, agentRuns, store, kafka,
                    new MissionRequest("Build", List.of(new AgentRunRequest(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A, "phase-b", Map.of()))),
                    "Unknown subroutine phase-b for routine " + FrameworkFixtures.ROUTINE_A);
        }
    }

    @Test
    void missingAgentRunsFailsValidation() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunStore agentRuns = new AgentRunStore();
        StubKafkaComponent kafka = new StubKafkaComponent();

        try (DefaultCamelContext context = context(FrameworkFixtures.projectionManager(store), FrameworkFixtures.routineCatalog(), agentRuns, kafka, "mission-route-test")) {
            context.start();
            assertMissionFailsBeforeSideEffects(context, agentRuns, store, kafka, new MissionRequest("Build", List.of()), "at least one AgentRunRequest");
            assertMissionFailsBeforeSideEffects(context, agentRuns, store, kafka, new MissionRequest("Build", null), "at least one AgentRunRequest");
        }
    }

    @Test
    void unknownPersonaOrRoutineFailsClearly() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunStore agentRuns = new AgentRunStore();
        StubKafkaComponent kafka = new StubKafkaComponent();

        try (DefaultCamelContext context = context(FrameworkFixtures.projectionManager(store), FrameworkFixtures.routineCatalog(), agentRuns, kafka, "mission-route-test")) {
            context.start();
            assertMissionFailsBeforeSideEffects(context, agentRuns, store, kafka,
                    new MissionRequest("Build", List.of(new AgentRunRequest("missing", FrameworkFixtures.ROUTINE_A, Map.of()))),
                    "Unknown persona: missing");
            assertMissionFailsBeforeSideEffects(context, agentRuns, store, kafka,
                    new MissionRequest("Build", List.of(new AgentRunRequest(FrameworkFixtures.PERSONA_A, "missing", Map.of()))),
                    "Unknown routine: missing");
        }
    }

    @Test
    void invalidSecondAgentRunFailsBeforeAnyMissionSideEffects() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        AgentRunStore agentRuns = new AgentRunStore();
        StubKafkaComponent[] kafkaRef = new StubKafkaComponent[1];
        StubKafkaComponent kafka = new StubKafkaComponent((topic, exchange) -> kafkaRef[0].emit(topic, exchange.getIn().getBody(String.class)));
        kafkaRef[0] = kafka;
        RoutineCatalog routines = new RoutineCatalog(List.of(routine(FrameworkFixtures.ROUTINE_A, "direct:routine-a")));
        FrameworkFixtures.set(manager, "routines", routines);
        AtomicInteger starts = new AtomicInteger();

        try (DefaultCamelContext context = context(manager, routines, agentRuns, kafka, "mission-route-test")) {
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:routine-a").process(exchange -> starts.incrementAndGet());
                }
            });
            context.start();

            MissionRequest request = new MissionRequest("Build", List.of(
                    new AgentRunRequest(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A, Map.of()),
                    new AgentRunRequest("missing-persona", FrameworkFixtures.ROUTINE_A, Map.of())));

            assertMissionFailsBeforeSideEffects(context, agentRuns, store, kafka, request, "Unknown persona: missing-persona");
            Thread.sleep(150);
            assertEquals(0, starts.get());
        }
    }

    @Test
    void asyncRoutineFailureUpdatesProjectionAfterAcceptedResponse() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        AgentRunStore agentRuns = new AgentRunStore();
        StubKafkaComponent kafka = new StubKafkaComponent();
        RoutineCatalog routines = new RoutineCatalog(List.of(routine(FrameworkFixtures.ROUTINE_A, "direct:failing-root-routine")));
        FrameworkFixtures.set(manager, "routines", routines);
        AtomicInteger starts = new AtomicInteger();

        try (DefaultCamelContext context = context(manager, routines, agentRuns, kafka, "mission-route-test")) {
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    errorHandler(noErrorHandler());
                    from("direct:failing-root-routine")
                            .process(exchange -> starts.incrementAndGet())
                            .throwException(new IllegalStateException("routine failed asynchronously"));
                }
            });
            context.start();

            @SuppressWarnings("unchecked")
            Map<String, Object> response = context.createProducerTemplate().requestBody(
                    "direct:start-mission",
                    new MissionRequest("Build", List.of(new AgentRunRequest(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A, Map.of()))),
                    Map.class);

            AgentRunHandle handle = firstHandle(response);
            assertEquals("accepted", handle.status());
            AgentRunScheduled scheduled = scheduledEvent(kafka, 0);
            context.createProducerTemplate().sendBody("direct:handle-agent-run-scheduled", scheduled);
            assertTrue(waitForEvent(store, handle.agentRunId(), "AGENT_RUN_FAILED"));
            context.createProducerTemplate().sendBody("direct:handle-agent-run-scheduled", scheduled);
            assertEquals(1, starts.get());
            assertFalse(events(store.get(handle.agentRunId())).contains("AGENT_RUN_COMPLETED"));
        }
    }

    @Test
    void kafkaScheduledConsumerReturnsBeforeRoutineCompletion() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        AgentRunStore agentRuns = new AgentRunStore();
        StubKafkaComponent kafka = new StubKafkaComponent();
        RoutineCatalog routines = new RoutineCatalog(List.of(routine(FrameworkFixtures.ROUTINE_A, "direct:blocked-root-routine")));
        FrameworkFixtures.set(manager, "routines", routines);
        CountDownLatch routineStarted = new CountDownLatch(1);
        CountDownLatch routineRelease = new CountDownLatch(1);

        try (DefaultCamelContext context = context(manager, routines, agentRuns, kafka, "mission-route-test")) {
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:blocked-root-routine")
                            .process(exchange -> routineStarted.countDown())
                            .process(exchange -> routineRelease.await(2, TimeUnit.SECONDS));
                }
            });
            context.start();

            @SuppressWarnings("unchecked")
            Map<String, Object> response = context.createProducerTemplate().requestBody(
                    "direct:start-mission",
                    new MissionRequest("Build", List.of(new AgentRunRequest(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A, Map.of()))),
                    Map.class);
            AgentRunHandle handle = firstHandle(response);

            long started = System.nanoTime();
            kafka.emit(SCHEDULED_TOPIC, kafka.sentTo(SCHEDULED_TOPIC).getFirst().getIn().getBody(String.class));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue(elapsedMs < 300, "Kafka scheduled-event consumer should return after async launch, not routine completion");
            assertTrue(routineStarted.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("AGENT_RUN_ACCEPTED", "AGENT_RUN_STARTED"), events(store.get(handle.agentRunId())));

            routineRelease.countDown();
            assertTrue(waitForEvent(store, handle.agentRunId(), "AGENT_RUN_COMPLETED"));
        }
    }

    @Test
    void duplicateScheduledEventsDoNotLaunchDuplicateRoutineExecutions() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        AgentRunStore agentRuns = new AgentRunStore();
        StubKafkaComponent kafka = new StubKafkaComponent();
        RoutineCatalog routines = new RoutineCatalog(List.of(routine(FrameworkFixtures.ROUTINE_A, "direct:counted-root-routine")));
        FrameworkFixtures.set(manager, "routines", routines);
        AtomicInteger starts = new AtomicInteger();
        CountDownLatch routineStarted = new CountDownLatch(1);
        CountDownLatch routineRelease = new CountDownLatch(1);

        try (DefaultCamelContext context = context(manager, routines, agentRuns, kafka, "mission-route-test")) {
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:counted-root-routine")
                            .process(exchange -> {
                                starts.incrementAndGet();
                                routineStarted.countDown();
                            })
                            .process(exchange -> routineRelease.await(2, TimeUnit.SECONDS));
                }
            });
            context.start();

            @SuppressWarnings("unchecked")
            Map<String, Object> response = context.createProducerTemplate().requestBody(
                    "direct:start-mission",
                    new MissionRequest("Build", List.of(new AgentRunRequest(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A, Map.of()))),
                    Map.class);
            AgentRunHandle handle = firstHandle(response);
            AgentRunScheduled scheduled = scheduledEvent(kafka, 0);

            context.createProducerTemplate().sendBody("direct:handle-agent-run-scheduled", scheduled);
            assertTrue(routineStarted.await(2, TimeUnit.SECONDS));
            context.createProducerTemplate().sendBody("direct:handle-agent-run-scheduled", scheduled);
            assertEquals(1, starts.get(), "duplicate while running should be ignored");

            routineRelease.countDown();
            assertTrue(waitForEvent(store, handle.agentRunId(), "AGENT_RUN_COMPLETED"));
            context.createProducerTemplate().sendBody("direct:handle-agent-run-scheduled", scheduled);
            assertEquals(1, starts.get(), "duplicate after completion should be ignored");
            assertEquals(List.of("AGENT_RUN_ACCEPTED", "AGENT_RUN_STARTED", "AGENT_RUN_COMPLETED"), events(store.get(handle.agentRunId())));
        }
    }

    @SuppressWarnings("unchecked")
    private static AgentRunHandle firstHandle(Map<String, Object> response) {
        return ((List<AgentRunHandle>) response.get("agentRuns")).getFirst();
    }

    private static void assertMissionFailsBeforeSideEffects(
            DefaultCamelContext context,
            AgentRunStore agentRuns,
            ProjectionStore store,
            StubKafkaComponent kafka,
            MissionRequest request,
            String expectedMessage) {
        Exception failure = assertThrows(Exception.class, () -> context.createProducerTemplate().requestBody("direct:start-mission", request, Map.class));
        assertTrue(messageChain(failure).contains(expectedMessage), "expected failure to include: " + expectedMessage + "\nActual: " + messageChain(failure));
        assertEquals(List.of(), kafka.sentTopics());
        assertEquals(0, agentRuns.missions().size());
        assertEquals(0, agentRuns.runs().size());
        assertEquals(0, store.all().size());
    }

    private static String messageChain(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return messages.toString();
    }
}
