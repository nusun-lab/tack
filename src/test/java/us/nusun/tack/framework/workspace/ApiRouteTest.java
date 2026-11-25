package us.nusun.tack.framework.workspace;

import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.Processor;
import org.apache.camel.component.platform.http.PlatformHttpComponent;
import org.apache.camel.component.platform.http.PlatformHttpEndpoint;
import org.apache.camel.component.platform.http.spi.PlatformHttpConsumer;
import org.apache.camel.support.DefaultConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.processor.PredicateValidationException;
import org.junit.jupiter.api.Test;
import us.nusun.tack.framework.run.AgentRunRequest;
import us.nusun.tack.framework.run.MissionRequest;
import us.nusun.tack.framework.tests.support.FrameworkFixtures;
import us.nusun.tack.framework.tests.support.TestCamelContexts;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ApiRouteTest {
    @Test
    void restBindingConvertsMissionInputAndSerializesSuccessBodies() throws Exception {
        try (var context = TestCamelContexts.quiet()) {
            bindApiDependencies(context);
            var endpoints = new HashMap<String, HttpConsumer>();
            var http = new PlatformHttpComponent();
            http.setEngine((endpoint, processor) -> {
                var consumer = new HttpConsumer(endpoint, processor);
                endpoints.put(endpoint.getPath(), consumer);
                return consumer;
            });
            context.addComponent("platform-http", http);
            context.addRoutes(new ApiRoute());
            context.addRoutes(new HttpApiRoute());
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:start-mission").process(exchange -> {
                        MissionRequest request = assertInstanceOf(MissionRequest.class, exchange.getMessage().getBody());
                        assertEquals("Build", request.goal());
                        var input = assertInstanceOf(Map.class, request.agentRuns().getFirst().input());
                        assertEquals(List.of(3, "note"), input.get("items"));
                        exchange.getMessage().setBody(Map.of("missionId", "mission-1", "status", "accepted"));
                    });
                }
            });
            context.start();

            var mapper = new ObjectMapper();
            assertEquals("mission-1", mapper.readTree(httpRequest(endpoints.get("/missions"), "POST", 202, """
                    {"goal":"Build","agentRuns":[{"personaId":"persona-a","routineId":"routine-a","input":{"items":[3,"note"]}}]}
                    """)).get("missionId").textValue());
            assertEquals("OK", mapper.readTree(httpRequest(endpoints.get("/workspace/view"), "GET", 200, null))
                    .get("status").textValue());
            assertEquals("RESET", mapper.readTree(httpRequest(endpoints.get("/workspace/reset"), "POST", 200, null))
                    .get("status").textValue());
        }
    }

    private static String httpRequest(HttpConsumer consumer, String method, int status, String input) throws Exception {
        Exchange exchange = consumer.getEndpoint().createExchange(ExchangePattern.InOut);
        exchange.getMessage().setHeader(Exchange.HTTP_METHOD, method);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(input);
        consumer.getProcessor().process(exchange);
        if (exchange.getException() != null) {
            throw exchange.getException();
        }
        assertEquals(status, exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));
        assertEquals("application/json", exchange.getMessage().getHeader(Exchange.CONTENT_TYPE));
        return exchange.getMessage().getBody(String.class);
    }

    private static final class HttpConsumer extends DefaultConsumer implements PlatformHttpConsumer {
        HttpConsumer(PlatformHttpEndpoint endpoint, Processor processor) {
            super(endpoint, processor);
        }

        @Override
        public PlatformHttpEndpoint getEndpoint() {
            return (PlatformHttpEndpoint) super.getEndpoint();
        }
    }

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
