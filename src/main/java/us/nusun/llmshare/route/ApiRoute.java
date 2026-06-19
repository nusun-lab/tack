package us.nusun.llmshare.route;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import us.nusun.llmshare.bean.ProjectMemoryStore;
import us.nusun.llmshare.model.DirectorStateRecord;
import us.nusun.llmshare.model.LlmJobRequest;
import us.nusun.llmshare.model.UserGoalRequest;
import us.nusun.llmshare.team.AgentPersonaRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class ApiRoute extends RouteBuilder {
    @Inject
    AgentPersonaRegistry agentPersonaRegistry;

    @Inject
    ProjectMemoryStore projectMemoryStore;

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

        rest("/goals/resume")
                .produces("application/json")
                .post()
                    .to("direct:resume-user-goals");

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
                            request.requestId() == null || request.requestId().isBlank() ? null : UUID.fromString(request.requestId().trim()),
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

        from("direct:resume-user-goals")
                .routeId("resume-user-goals")
                .process(exchange -> {
                    String requestedId = exchange.getIn().getHeader("requestId", String.class);
                    List<DirectorStateRecord> states = resolveDirectorStates(requestedId);
                    exchange.setProperty("directorStatesToResume", states);
                    String directorRoleId = agentPersonaRegistry.allPersonas().stream()
                            .filter(persona -> "director".equals(persona.roleSlug()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException("Director persona is not registered"))
                            .roleId();
                    exchange.setProperty("directorRoleId", directorRoleId);
                    exchange.setProperty("directorRouteUri", agentPersonaRegistry.getRequired(directorRoleId).routeUri());
                })
                .split(exchangeProperty("directorStatesToResume"))
                    .process(exchange -> {
                        DirectorStateRecord state = exchange.getIn().getBody(DirectorStateRecord.class);
                        exchange.getIn().setBody(LlmJobRequest.fromPrompt(
                                UUID.fromString(state.requestId()),
                                exchange.getProperty("directorRoleId", String.class),
                                "director",
                                state.goal(),
                                null,
                                null
                        ));
                    })
                    .toD("${exchangeProperty.directorRouteUri}")
                .end()
                .process(exchange -> {
                    @SuppressWarnings("unchecked")
                    List<DirectorStateRecord> states = exchange.getProperty("directorStatesToResume", List.class);
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("status", "RESUMED");
                    response.put("resumedCount", states.size());
                    response.put("requestIds", states.stream().map(DirectorStateRecord::requestId).toList());
                    exchange.getIn().setBody(response);
                })
                .marshal().json(JsonLibrary.Jackson)
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200))
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

    private List<DirectorStateRecord> resolveDirectorStates(String requestedId) {
        if (requestedId != null && !requestedId.isBlank()) {
            DirectorStateRecord state = projectMemoryStore.getDirectorState(requestedId);
            if (state == null) {
                throw new IllegalArgumentException("Unknown requestId: " + requestedId);
            }
            return List.of(state);
        }
        return projectMemoryStore.snapshotDirectorStates().values().stream()
                .sorted((left, right) -> safe(right.lastUpdatedAt()).compareTo(safe(left.lastUpdatedAt())))
                .toList();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
