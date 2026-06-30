package us.nusun.llmshare.framework.route;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.apache.camel.support.processor.PredicateValidationException;
import us.nusun.llmshare.framework.model.UserGoalRequest;

@ApplicationScoped
public class ApiRoute extends RouteBuilder {
    @Override
    public void configure() {
        onException(PredicateValidationException.class)
                .handled(false)
                .logStackTrace(false)
                .logExhausted(false)
                .log(LoggingLevel.DEBUG, "API request validation failed: ${exception.message}");

        from("direct:submit-user-goal")
                .routeId("submit-user-goal")
                .unmarshal().json(JsonLibrary.Jackson, UserGoalRequest.class)
                .validate(simple("${body.goal} != null && ${body.goal.trim()} != ''"))
                .choice()
                    .when(simple("${body.requestId} != null && ${body.requestId.trim()} != ''"))
                        .setHeader("requestId", simple("${body.requestId.trim()}"))
                .end()
                .setBody(simple("${body.goal}"))
                .to("direct:start-mission")
                .removeHeaders("*")
                .marshal().json(JsonLibrary.Jackson)
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
                .marshal().json(JsonLibrary.Jackson)
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200))
                .setHeader(Exchange.CONTENT_TYPE, constant("application/json"));

        from("direct:workspace-view")
                .routeId("workspace-view")
                .bean("workspaceViewService", "view")
                .marshal().json(JsonLibrary.Jackson)
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200))
                .setHeader(Exchange.CONTENT_TYPE, constant("application/json"));
    }
}
