package us.nusun.tack.framework.workspace;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.support.processor.PredicateValidationException;
import us.nusun.tack.framework.run.MissionRequest;

@ApplicationScoped
public class ApiRoute extends RouteBuilder {
    @Override
    public void configure() {
        onException(PredicateValidationException.class, IllegalArgumentException.class)
                .handled(false)
                .logStackTrace(false)
                .logExhausted(false)
                .log(LoggingLevel.DEBUG, "API request validation failed: ${exception.message}");

        from("direct:submit-mission")
                .routeId("submit-mission")
                .validate(body().isInstanceOf(MissionRequest.class))
                .validate(simple("${body.goal} != null && ${body.goal.trim()} != ''"))
                .validate(simple("${body.agentRuns} != null && ${body.agentRuns.size} > 0"))
                .to("direct:start-mission")
                .removeHeaders("*")
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(202))
                .setHeader(Exchange.CONTENT_TYPE, constant("application/json"));

        from("direct:perform-llm-reset")
                .routeId("perform-llm-reset")
                .choice()
                    .when(header("requestId").isNotNull())
                        .bean("resetService", "resetRequest(${header.requestId})")
                    .otherwise()
                        .bean("resetService", "reset")
                .end()
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200))
                .setHeader(Exchange.CONTENT_TYPE, constant("application/json"));

        from("direct:workspace-view")
                .routeId("workspace-view")
                .bean("workspaceViewService", "view")
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200))
                .setHeader(Exchange.CONTENT_TYPE, constant("application/json"));
    }
}
