package us.nusun.llmshare.framework.tests.component;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.inventory.RoutineCatalog;
import us.nusun.llmshare.framework.model.AgentRunHandle;
import us.nusun.llmshare.framework.model.AgentRunProjection;
import us.nusun.llmshare.framework.model.AgentRunRequest;
import us.nusun.llmshare.framework.model.MissionRequest;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.projection.AgentRunProjectionManager;
import us.nusun.llmshare.framework.projection.ProjectionStore;
import us.nusun.llmshare.framework.route.MissionRoute;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;
import us.nusun.llmshare.framework.tests.support.TestCamelContexts;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissionRouteAsyncTest {
    @Test
    void startMissionAcceptsOneRunWithoutWaitingForLongRoutine() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        RoutineCatalog routines = new RoutineCatalog(List.of(routine(FrameworkFixtures.ROUTINE_A, "direct:slow-root-routine")));
        FrameworkFixtures.set(manager, "routines", routines);
        CountDownLatch routineCompleted = new CountDownLatch(1);

        try (DefaultCamelContext context = context(manager, routines)) {
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
            assertNotNull(store.get(handle.agentRunId()));
            assertEquals(List.of("AGENT_RUN_STARTED"), events(store.get(handle.agentRunId())));

            assertTrue(routineCompleted.await(2, TimeUnit.SECONDS));
            assertTrue(waitForEvent(store, handle.agentRunId(), "AGENT_RUN_COMPLETED"));
        }
    }

    @Test
    void startMissionAcceptsMultipleTopLevelSiblingRuns() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        RoutineCatalog routines = new RoutineCatalog(List.of(
                routine(FrameworkFixtures.ROUTINE_A, "direct:routine-a"),
                routine(FrameworkFixtures.ROUTINE_B, "direct:routine-b")));
        FrameworkFixtures.set(manager, "routines", routines);

        try (DefaultCamelContext context = context(manager, routines)) {
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
            assertTrue(waitForEvent(store, handles.get(0).agentRunId(), "AGENT_RUN_COMPLETED"));
            assertTrue(waitForEvent(store, handles.get(1).agentRunId(), "AGENT_RUN_COMPLETED"));
        }
    }

    @Test
    void missingAgentRunsFailsValidation() throws Exception {
        try (DefaultCamelContext context = context(FrameworkFixtures.projectionManager(FrameworkFixtures.projectionStore()), FrameworkFixtures.routineCatalog())) {
            context.start();
            assertThrows(Exception.class, () -> context.createProducerTemplate().requestBody(
                    "direct:start-mission", new MissionRequest("Build", List.of()), Map.class));
        }
    }

    @Test
    void unknownPersonaOrRoutineFailsClearly() throws Exception {
        try (DefaultCamelContext context = context(FrameworkFixtures.projectionManager(FrameworkFixtures.projectionStore()), FrameworkFixtures.routineCatalog())) {
            context.start();
            assertThrows(Exception.class, () -> context.createProducerTemplate().requestBody(
                    "direct:start-mission", new MissionRequest("Build", List.of(new AgentRunRequest("missing", FrameworkFixtures.ROUTINE_A, Map.of()))), Map.class));
            assertThrows(Exception.class, () -> context.createProducerTemplate().requestBody(
                    "direct:start-mission", new MissionRequest("Build", List.of(new AgentRunRequest(FrameworkFixtures.PERSONA_A, "missing", Map.of()))), Map.class));
        }
    }

    @Test
    void asyncRoutineFailureUpdatesProjectionAfterAcceptedResponse() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        RoutineCatalog routines = new RoutineCatalog(List.of(routine(FrameworkFixtures.ROUTINE_A, "direct:failing-root-routine")));
        FrameworkFixtures.set(manager, "routines", routines);

        try (DefaultCamelContext context = context(manager, routines)) {
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    errorHandler(noErrorHandler());
                    from("direct:failing-root-routine")
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
            assertTrue(waitForEvent(store, handle.agentRunId(), "AGENT_RUN_FAILED"));
            assertFalse(events(store.get(handle.agentRunId())).contains("AGENT_RUN_COMPLETED"));
        }
    }

    private static DefaultCamelContext context(AgentRunProjectionManager manager, RoutineCatalog routines) throws Exception {
        DefaultCamelContext context = TestCamelContexts.quiet();
        context.getShutdownStrategy().setTimeout(1);
        context.getShutdownStrategy().setSuppressLoggingOnTimeout(true);
        MissionRoute route = new MissionRoute();
        FrameworkFixtures.set(route, "personas", FrameworkFixtures.personaCatalog());
        FrameworkFixtures.set(route, "routines", routines);
        FrameworkFixtures.set(route, "projections", manager);
        context.addRoutes(route);
        return context;
    }

    private static RouteBuilder noopRoute(String uri) {
        return new RouteBuilder() {
            @Override
            public void configure() {
                from(uri).setBody(constant("ok"));
            }
        };
    }

    private static RoutineDefinition routine(String id, String routeUri) {
        return RoutineDefinition.builder(id).name(id).description(id).routeUri(routeUri).build();
    }

    @SuppressWarnings("unchecked")
    private static AgentRunHandle firstHandle(Map<String, Object> response) {
        return ((List<AgentRunHandle>) response.get("agentRuns")).getFirst();
    }

    private static boolean waitForEvent(ProjectionStore store, String runId, String event) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            AgentRunProjection projection = store.get(runId);
            if (projection != null && events(projection).contains(event)) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }

    private static List<String> events(AgentRunProjection projection) {
        return projection.timeline().stream().map(item -> String.valueOf(item.get("type"))).toList();
    }
}
