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
import us.nusun.llmshare.framework.model.Mission;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.projection.AgentRunProjectionManager;
import us.nusun.llmshare.framework.projection.ProjectionStore;
import us.nusun.llmshare.framework.route.MissionRoute;
import us.nusun.llmshare.framework.state.AgentRun;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;
import us.nusun.llmshare.framework.tests.support.TestCamelContexts;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRunIsolationRouteTest {
    @Test
    void startAgentRunReturnsHandleImmediatelyAndChildCompletesLater() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        RoutineCatalog routines = routines(routine("test/parent", "direct:test-parent"), routine("test/child", "direct:slow-child"));
        FrameworkFixtures.set(manager, "routines", routines);
        CountDownLatch childStarted = new CountDownLatch(1);
        CountDownLatch childRelease = new CountDownLatch(1);

        try (DefaultCamelContext context = context(manager, routines)) {
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

            assertTrue(childStarted.await(2, TimeUnit.SECONDS));
            AgentRunProjection startedProjection = store.get(handle.agentRunId());
            assertNotNull(startedProjection);
            assertEquals(List.of("AGENT_RUN_STARTED"), events(startedProjection));

            childRelease.countDown();
            assertTrue(waitForEvent(store, handle.agentRunId(), "AGENT_RUN_COMPLETED"));
            AgentRunProjection completed = store.get(handle.agentRunId());
            assertEquals(parent.id(), completed.parentAgentRunId());
            assertEquals(List.of("AGENT_RUN_STARTED", "AGENT_RUN_COMPLETED"), events(completed));
        }
    }

    @Test
    void parentRoutineDoesNotWaitForScheduledChildCompletion() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        RoutineCatalog routines = routines(routine("test/parent", "direct:parent-schedules-child"), routine("test/child", "direct:blocked-child"));
        FrameworkFixtures.set(manager, "routines", routines);
        CountDownLatch childRelease = new CountDownLatch(1);

        try (DefaultCamelContext context = context(manager, routines)) {
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:parent-schedules-child")
                            .setBody(constant(new AgentRunRequest(FrameworkFixtures.PERSONA_A, "test/child", Map.of())))
                            .to("direct:start-agent-run")
                            .setProperty("parentContinuedAfterScheduling", constant(true));

                    from("direct:blocked-child")
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
            String childRunId = store.all().stream()
                    .filter(projection -> parent.id().equals(projection.parentAgentRunId()))
                    .findFirst()
                    .orElseThrow()
                    .agentRunId();
            childRelease.countDown();
            assertTrue(waitForEvent(store, childRunId, "AGENT_RUN_COMPLETED"));
        }
    }

    @Test
    void childFailureLaterUpdatesOnlyChildProjection() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        RoutineCatalog routines = routines(routine("test/parent", "direct:test-parent"), routine("test/failing-child", "direct:failing-child"));
        FrameworkFixtures.set(manager, "routines", routines);

        try (DefaultCamelContext context = context(manager, routines)) {
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
            assertTrue(waitForEvent(store, handle.agentRunId(), "AGENT_RUN_FAILED"));
            assertFalse(events(store.get(handle.agentRunId())).contains("AGENT_RUN_COMPLETED"));
            assertEquals("active", parent.status());
        }
    }

    @Test
    void schedulingValidationFailureRestoresParentState() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        RoutineCatalog routines = routines(routine("test/parent", "direct:test-parent"), routine("test/child", "direct:test-child"));
        FrameworkFixtures.set(manager, "routines", routines);

        try (DefaultCamelContext context = context(manager, routines)) {
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
            assertFalse(exchange.getProperties().containsKey("agentRunExchangeScopeStack"));
            assertEquals(0, store.all().size());
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

    private static Exchange preparedExchange(DefaultCamelContext context, AgentRun parent, AgentRunRequest request) {
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

    private static RoutineCatalog routines(RoutineDefinition... routines) {
        return new RoutineCatalog(List.of(routines));
    }

    private static RoutineDefinition routine(String id, String routeUri) {
        return RoutineDefinition.builder(id).name(id).description(id).routeUri(routeUri).build();
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
        return projection.timeline().stream().map(row -> String.valueOf(row.get("type"))).toList();
    }
}
