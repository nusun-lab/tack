package us.nusun.llmshare.route;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import us.nusun.llmshare.model.LlmJobRequest;
import us.nusun.llmshare.model.UserGoalRequest;

@ApplicationScoped
public class ApiRoute extends RouteBuilder {
    @Override
    public void configure() {
        restConfiguration()
                .component("platform-http")
                .dataFormatProperty("prettyPrint", "true");

        rest("/goals")
                .produces("application/json")
                .consumes("application/json")
                .post()
                    .type(UserGoalRequest.class)
                    .to("direct:submit-user-goal");

        rest("/workspace/reset")
                .produces("application/json")
                .post()
                    .to("direct:perform-llm-reset");

        rest("/workspace/view")
                .produces("application/json")
                .get()
                    .to("direct:workspace-view");

        from("direct:submit-user-goal")
                .routeId("submit-user-goal")
                .unmarshal().json(JsonLibrary.Jackson, UserGoalRequest.class)
                .process(exchange -> {
                    UserGoalRequest request = exchange.getIn().getBody(UserGoalRequest.class);
                    request.validate();
                    exchange.getIn().setBody(LlmJobRequest.fromPrompt(
                            null,
                            "software-team/director",
                            "director",
                            request.goal(),
                            null,
                            null
                    ));
                })
                .to("direct:software-team-director")
                .marshal().json(JsonLibrary.Jackson)
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200))
                .setHeader(Exchange.CONTENT_TYPE, constant("application/json"));

        from("direct:perform-llm-reset")
                .routeId("perform-llm-reset")
                .bean("resetService", "reset")
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
