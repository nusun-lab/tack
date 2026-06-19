package us.nusun.llmshare.route;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import us.nusun.llmshare.model.LlmJobRequest;
import us.nusun.llmshare.model.UserGoalRequest;
import us.nusun.llmshare.team.AgentPersonaRegistry;

@ApplicationScoped
public class ApiRoute extends RouteBuilder {
    @Inject
    AgentPersonaRegistry agentPersonaRegistry;

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
                    String directorRoleId = agentPersonaRegistry.allPersonas().stream()
                            .filter(persona -> "director".equals(persona.roleSlug()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException("Director persona is not registered"))
                            .roleId();
                    String directorRouteUri = agentPersonaRegistry.getRequired(directorRoleId).routeUri();
                    exchange.setProperty("directorRouteUri", directorRouteUri);
                    exchange.getIn().setBody(LlmJobRequest.fromPrompt(
                            null,
                            directorRoleId,
                            "director",
                            request.goal(),
                            null,
                            null
                    ));
                })
                .toD("${exchangeProperty.directorRouteUri}")
                .removeHeaders("*")
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
