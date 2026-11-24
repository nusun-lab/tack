package us.nusun.tack.framework.tests.component;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.processor.PredicateValidationException;
import org.junit.jupiter.api.Test;
import us.nusun.tack.framework.route.ApiRoute;
import us.nusun.tack.framework.model.AgentRunRequest;
import us.nusun.tack.framework.model.MissionRequest;
import us.nusun.tack.framework.tests.support.FrameworkFixtures;
import us.nusun.tack.framework.tests.support.TestCamelContexts;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ApiRouteTest {
    @Test
    void submitMissionUsesTypedRequestsAndReturnsAcceptedResponse() throws Exception {
        try (DefaultCamelContext context = TestCamelContexts.quiet()) {
            FrameworkFixtures.bindCatalogBeans(context);
            bindApiDependencies(context);
            context.addRoutes(new ApiRoute());
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:start-mission")
                            .process(exchange -> exchange.getIn().setBody(Map.of(
                                    "missionId", "mission-1",
                                    "status", "accepted",
                                    "agentRuns", List.of(Map.of(
                                            "agentRunId", "run-1",
                                            "missionId", "mission-1",
                                            "personaId", "persona-a",
                                            "routineId", "routine-a",
                                            "status", "accepted")))));
                }
            });
            context.start();

            Exchange result = context.createProducerTemplate().request(
                    "direct:submit-mission",
                    exchange -> exchange.getIn().setBody(new MissionRequest("Build a parking lot manager app.",
                            List.of(new AgentRunRequest("persona-a", "routine-a", Map.of())))));

            var response = assertInstanceOf(Map.class, result.getIn().getBody());
            assertEquals(202, result.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));
            assertEquals("mission-1", response.get("missionId"));
            assertEquals("run-1", assertInstanceOf(Map.class,
                    assertInstanceOf(List.class, response.get("agentRuns")).getFirst()).get("agentRunId"));
        }
    }

    @Test
    void submitMissionRejectsBlankGoalWithCamelValidation() throws Exception {
        try (DefaultCamelContext context = TestCamelContexts.quiet()) {
            FrameworkFixtures.bindCatalogBeans(context);
            bindApiDependencies(context);
            context.addRoutes(new ApiRoute());
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:start-mission").setBody(constant(Map.of()));
                }
            });
            context.start();

            Exception failure = assertThrows(Exception.class, () -> context.createProducerTemplate().requestBody(
                    "direct:submit-mission",
                    new MissionRequest("   ", List.of(new AgentRunRequest("persona-a", "routine-a", Map.of())))));

            assertCausedBy(failure, PredicateValidationException.class);
        }
    }

    @Test
    void submitMissionRejectsEmptyAgentRunsWithCamelValidation() throws Exception {
        try (DefaultCamelContext context = TestCamelContexts.quiet()) {
            FrameworkFixtures.bindCatalogBeans(context);
            bindApiDependencies(context);
            context.addRoutes(new ApiRoute());
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:start-mission").setBody(constant(Map.of()));
                }
            });
            context.start();

            Exception failure = assertThrows(Exception.class, () -> context.createProducerTemplate().requestBody(
                    "direct:submit-mission",
                    new MissionRequest("Build", List.of())));

            assertCausedBy(failure, PredicateValidationException.class);
        }
    }

    private static void assertCausedBy(Throwable failure, Class<?> type) {
        Throwable current = failure;
        while (current != null && !type.isInstance(current)) {
            current = current.getCause();
        }
        assertEquals(type, current == null ? null : current.getClass());
    }

    private static void bindApiDependencies(DefaultCamelContext context) {
        context.getRegistry().bind("resetService", new TestResetService());
        context.getRegistry().bind("workspaceViewService", new TestWorkspaceViewService());
    }

    public static final class TestResetService {
        public Map<String, Object> reset() {
            return Map.of("status", "RESET");
        }

        public Map<String, Object> resetRequest(String requestId) {
            return Map.of("status", "RESET", "requestId", requestId);
        }
    }

    public static final class TestWorkspaceViewService {
        public Map<String, Object> view() {
            return Map.of("status", "OK");
        }
    }
}
