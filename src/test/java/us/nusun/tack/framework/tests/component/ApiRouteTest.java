package us.nusun.tack.framework.tests.component;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.processor.PredicateValidationException;
import org.junit.jupiter.api.Test;
import us.nusun.tack.framework.route.ApiRoute;
import us.nusun.tack.framework.tests.support.FrameworkFixtures;
import us.nusun.tack.framework.tests.support.TestCamelContexts;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiRouteTest {
    @Test
    void submitMissionUsesExplicitAgentRunsAndReturnsAcceptedJson() throws Exception {
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
                    exchange -> exchange.getIn().setBody("""
                            {"goal":"Build a parking lot manager app.","agentRuns":[{"personaId":"persona-a","routineId":"routine-a"}]}
                            """));

            String response = result.getIn().getBody(String.class);
            assertEquals(202, result.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));
            assertTrue(response.contains("\"missionId\":\"mission-1\""));
            assertTrue(response.contains("\"agentRunId\":\"run-1\""));
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
                    "{\"goal\":\"   \",\"agentRuns\":[{\"personaId\":\"persona-a\",\"routineId\":\"routine-a\"}]}"));

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
                    "{\"goal\":\"Build\",\"agentRuns\":[]}"));

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
