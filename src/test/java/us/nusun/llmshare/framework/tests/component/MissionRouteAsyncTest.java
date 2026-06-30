package us.nusun.llmshare.framework.tests.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.inventory.RoutineCatalog;
import us.nusun.llmshare.framework.model.AgentRunProjection;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissionRouteAsyncTest {
    @Test
    void startMissionAcceptsRootRunWithoutWaitingForLongRoutine() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        RoutineCatalog routines = new RoutineCatalog(List.of(rootRoutine("direct:slow-root-routine")));
        FrameworkFixtures.set(manager, "routines", routines);
        MissionRoute route = missionRoute(manager, routines);
        CountDownLatch routineCompleted = new CountDownLatch(1);

        try (DefaultCamelContext context = TestCamelContexts.quiet()) {
            context.addRoutes(route);
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
                    "Build a parking app",
                    "requestId",
                    "mission-async",
                    Map.class);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue(elapsedMs < 300, "mission acceptance should not wait for the 500ms root routine");
            assertEquals("mission-async", response.get("missionId"));
            assertEquals("accepted", response.get("status"));
            String rootRunId = (String) response.get("rootAgentRunId");
            assertNotNull(rootRunId);
            AgentRunProjection startedProjection = store.get(rootRunId);
            assertNotNull(startedProjection);
            assertEquals(List.of("AGENT_RUN_STARTED"), events(startedProjection));

            assertTrue(routineCompleted.await(2, TimeUnit.SECONDS));
            assertTrue(waitForEvent(store, rootRunId, "AGENT_RUN_COMPLETED"));
            AgentRunProjection completedProjection = store.get(rootRunId);
            assertEquals(List.of("AGENT_RUN_STARTED", "AGENT_RUN_COMPLETED"), events(completedProjection));
        }
    }

    @Test
    void asyncRootRoutineFailureUpdatesProjectionAfterAcceptedResponse() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        RoutineCatalog routines = new RoutineCatalog(List.of(rootRoutine("direct:failing-root-routine")));
        FrameworkFixtures.set(manager, "routines", routines);
        MissionRoute route = missionRoute(manager, routines);

        try (DefaultCamelContext context = TestCamelContexts.quiet()) {
            context.addRoutes(route);
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    errorHandler(noErrorHandler());

                    from("direct:failing-root-routine")
                            .throwException(new IllegalStateException("root routine failed asynchronously"));
                }
            });
            context.start();

            @SuppressWarnings("unchecked")
            Map<String, Object> response = context.createProducerTemplate().requestBody(
                    "direct:start-mission",
                    "Build a parking app",
                    Map.class);

            String rootRunId = (String) response.get("rootAgentRunId");
            assertNotNull(rootRunId);
            assertTrue(waitForEvent(store, rootRunId, "AGENT_RUN_FAILED"));
            AgentRunProjection projection = store.get(rootRunId);
            assertEquals(List.of("AGENT_RUN_STARTED", "AGENT_RUN_FAILED"), events(projection));
            assertFalse(events(projection).contains("AGENT_RUN_COMPLETED"));
        }
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

    private static MissionRoute missionRoute(AgentRunProjectionManager manager, RoutineCatalog routines) {
        MissionRoute missionRoute = new MissionRoute();
        FrameworkFixtures.set(missionRoute, "personas", FrameworkFixtures.personaCatalog());
        FrameworkFixtures.set(missionRoute, "routines", routines);
        FrameworkFixtures.set(missionRoute, "projections", manager);
        FrameworkFixtures.set(missionRoute, "objectMapper", new ObjectMapper());
        FrameworkFixtures.set(missionRoute, "initialAgentRunPersonaId", FrameworkFixtures.PERSONA_A);
        FrameworkFixtures.set(missionRoute, "initialAgentRunRoutineId", FrameworkFixtures.ROUTINE_A);
        return missionRoute;
    }

    private static RoutineDefinition rootRoutine(String routeUri) {
        return RoutineDefinition.builder(FrameworkFixtures.ROUTINE_A)
                .name("Root Routine")
                .description("Root routine for async mission tests.")
                .routeUri(routeUri)
                .build();
    }
}
