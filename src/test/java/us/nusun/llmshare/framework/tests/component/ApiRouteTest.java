package us.nusun.llmshare.framework.tests.component;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.processor.PredicateValidationException;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.route.ApiRoute;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;
import us.nusun.llmshare.framework.tests.support.TestCamelContexts;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiRouteTest {
    @Test
    void submitUserGoalUsesCamelJsonBindingValidationAndSimpleExpressions() throws Exception {
        try (DefaultCamelContext context = TestCamelContexts.quiet()) {
            FrameworkFixtures.bindCatalogBeans(context);
            bindApiDependencies(context);
            context.addRoutes(new ApiRoute());
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:start-mission")
                            .process(exchange -> exchange.getIn().setBody(Map.of(
                                    "requestId", exchange.getIn().getHeader("requestId", String.class),
                                    "goal", exchange.getIn().getBody(String.class))));
                }
            });
            context.start();

            Exchange result = context.createProducerTemplate().request(
                    "direct:submit-user-goal",
                    exchange -> exchange.getIn().setBody("""
                            {"goal":"Build a parking lot manager app.","requestId":"  mission-1  "}
                            """));

            String response = result.getIn().getBody(String.class);
            org.junit.jupiter.api.Assertions.assertTrue(response.contains("\"requestId\":\"mission-1\""));
            org.junit.jupiter.api.Assertions.assertTrue(response.contains("\"goal\":\"Build a parking lot manager app.\""));
        }
    }

    @Test
    void submitUserGoalRejectsBlankGoalWithCamelValidation() throws Exception {
        try (DefaultCamelContext context = TestCamelContexts.quiet()) {
            FrameworkFixtures.bindCatalogBeans(context);
            bindApiDependencies(context);
            context.addRoutes(new ApiRoute());
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:start-mission")
                            .setBody(constant(Map.of()));
                }
            });
            context.start();

            Exception failure = assertThrows(
                    Exception.class,
                    () -> context.createProducerTemplate().requestBody(
                            "direct:submit-user-goal",
                            "{\"goal\":\"   \"}"));

            Throwable current = failure;
            while (current != null && !(current instanceof PredicateValidationException)) {
                current = current.getCause();
            }
            assertEquals(PredicateValidationException.class, current == null ? null : current.getClass());
        }
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
