package us.nusun.llmshare.route;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.model.dataformat.JsonLibrary;
import us.nusun.llmshare.model.DirectorStateRecord;
import us.nusun.llmshare.bean.ProjectMemoryStore;
import us.nusun.llmshare.model.ProjectBasicsStateRecord;
import us.nusun.llmshare.model.ScratchpadStateRecord;

import java.time.Instant;
import java.util.Map;

@ApplicationScoped
public class WorkspaceStateRoute extends RouteBuilder {
    private static final int SCRATCHPAD_LIMIT = 1000;

    @Inject
    ProjectMemoryStore projectMemoryStore;

    @Override
    public void configure() {
        from("direct:workspace-project-basics-store")
                .routeId("workspace-project-basics-store")
                .process(exchange -> {
                    String requestId = projectMemoryStore.projectKey(exchange);
                    String projectBasics = exchange.getProperty("projectBasics", String.class);
                    ProjectBasicsStateRecord record = new ProjectBasicsStateRecord(
                            requestId,
                            projectBasics,
                            exchange.getIn().getHeader("agent", String.class),
                            Instant.now().toString());
                    exchange.setProperty("workspaceStateKey", requestId);
                    exchange.getIn().setBody(record);
                    projectMemoryStore.applyProjectBasics(record);
                })
                .choice()
                    .when(simple("{{workspace.state.enabled:true}} == true"))
                        .marshal().json(JsonLibrary.Jackson)
                        .toD("kafka:{{workspace.topic.project-basics}}?key=${exchangeProperty.workspaceStateKey}")
                .end();

        from("direct:workspace-project-basics-delete")
                .routeId("workspace-project-basics-delete")
                .process(exchange -> {
                    String requestId = exchange.getProperty("requestId", String.class);
                    exchange.setProperty("workspaceStateKey", requestId);
                    projectMemoryStore.removeProjectBasics(requestId);
                    exchange.getIn().setBody(null);
                })
                .choice()
                    .when(simple("{{workspace.state.enabled:true}} == true"))
                        .toD("kafka:{{workspace.topic.project-basics}}?key=${exchangeProperty.workspaceStateKey}")
                .end();

        from("direct:workspace-director-state-store")
                .routeId("workspace-director-state-store")
                .process(exchange -> {
                    String requestId = projectMemoryStore.projectKey(exchange);
                    String goal = exchange.getProperty("requestPrompt", String.class);
                    @SuppressWarnings("unchecked")
                    Map<String, String> assignments = (Map<String, String>) exchange.getProperty("directorAssignmentsMap", Map.class);
                    DirectorStateRecord record = new DirectorStateRecord(
                            requestId,
                            goal,
                            assignments == null ? Map.of() : assignments,
                            Instant.now().toString());
                    exchange.setProperty("workspaceStateKey", requestId);
                    exchange.getIn().setBody(record);
                    projectMemoryStore.applyDirectorState(record);
                })
                .choice()
                    .when(simple("{{workspace.state.enabled:true}} == true"))
                        .marshal().json(JsonLibrary.Jackson)
                        .toD("kafka:{{workspace.topic.director-state}}?key=${exchangeProperty.workspaceStateKey}")
                .end();

        from("direct:workspace-director-state-delete")
                .routeId("workspace-director-state-delete")
                .process(exchange -> {
                    String requestId = exchange.getProperty("requestId", String.class);
                    exchange.setProperty("workspaceStateKey", requestId);
                    projectMemoryStore.removeDirectorState(requestId);
                    exchange.getIn().setBody(null);
                })
                .choice()
                    .when(simple("{{workspace.state.enabled:true}} == true"))
                        .toD("kafka:{{workspace.topic.director-state}}?key=${exchangeProperty.workspaceStateKey}")
                .end();

        from("direct:workspace-scratchpad-store")
                .routeId("workspace-scratchpad-store")
                .process(exchange -> {
                    String requestId = projectMemoryStore.projectKey(exchange);
                    String roleId = exchange.getProperty("workspaceScratchpadRoleId", String.class);
                    String latestNote = exchange.getProperty("agentScratchpad", String.class);
                    String existingScratchpad = projectMemoryStore.getAgentScratchpad(exchange, roleId);
                    String scratchpad = combineScratchpad(existingScratchpad, latestNote);
                    int messageCount = projectMemoryStore.currentScratchpadMessageCount(requestId, roleId) + 1;
                    ScratchpadStateRecord record = new ScratchpadStateRecord(
                            requestId,
                            roleId,
                            scratchpad,
                            messageCount,
                            Instant.now().toString());
                    exchange.setProperty("workspaceStateKey", requestId + "::" + roleId);
                    exchange.getIn().setBody(record);
                    projectMemoryStore.applyScratchpad(record);
                })
                .choice()
                    .when(simple("{{workspace.state.enabled:true}} == true"))
                        .marshal().json(JsonLibrary.Jackson)
                        .toD("kafka:{{workspace.topic.scratchpads}}?key=${exchangeProperty.workspaceStateKey}")
                .end();

        from("direct:workspace-scratchpad-delete")
                .routeId("workspace-scratchpad-delete")
                .process(exchange -> {
                    String requestId = exchange.getProperty("requestId", String.class);
                    String roleId = exchange.getProperty("workspaceScratchpadRoleId", String.class);
                    exchange.setProperty("workspaceStateKey", requestId + "::" + roleId);
                    projectMemoryStore.removeScratchpad(requestId, roleId);
                    exchange.getIn().setBody(null);
                })
                .choice()
                    .when(simple("{{workspace.state.enabled:true}} == true"))
                        .toD("kafka:{{workspace.topic.scratchpads}}?key=${exchangeProperty.workspaceStateKey}")
                .end();

        from("kafka:{{workspace.topic.project-basics}}"
                + "?groupId={{workspace.state.consumer.group}}"
                + "&autoOffsetReset=earliest"
                + "&maxPollRecords=100")
                .routeId("workspace-project-basics-consumer")
                .autoStartup("{{workspace.state.enabled:true}}")
                .choice()
                    .when(body().isNull())
                        .process(exchange -> projectMemoryStore.removeProjectBasics(exchange.getIn().getHeader(KafkaConstants.KEY, String.class)))
                    .otherwise()
                        .unmarshal().json(JsonLibrary.Jackson, ProjectBasicsStateRecord.class)
                        .bean(projectMemoryStore, "applyProjectBasics")
                .end();

        from("kafka:{{workspace.topic.scratchpads}}"
                + "?groupId={{workspace.state.consumer.group}}"
                + "&autoOffsetReset=earliest"
                + "&maxPollRecords=100")
                .routeId("workspace-scratchpads-consumer")
                .autoStartup("{{workspace.state.enabled:true}}")
                .choice()
                    .when(body().isNull())
                        .process(exchange -> {
                            String key = exchange.getIn().getHeader(KafkaConstants.KEY, String.class);
                            if (key == null) {
                                return;
                            }
                            int split = key.indexOf("::");
                            if (split < 0) {
                                return;
                            }
                            projectMemoryStore.removeScratchpad(key.substring(0, split), key.substring(split + 2));
                        })
                    .otherwise()
                        .unmarshal().json(JsonLibrary.Jackson, ScratchpadStateRecord.class)
                        .bean(projectMemoryStore, "applyScratchpad")
                .end();

        from("kafka:{{workspace.topic.director-state}}"
                + "?groupId={{workspace.state.consumer.group}}"
                + "&autoOffsetReset=earliest"
                + "&maxPollRecords=100")
                .routeId("workspace-director-state-consumer")
                .autoStartup("{{workspace.state.enabled:true}}")
                .choice()
                    .when(body().isNull())
                        .process(exchange -> projectMemoryStore.removeDirectorState(exchange.getIn().getHeader(KafkaConstants.KEY, String.class)))
                    .otherwise()
                        .unmarshal().json(JsonLibrary.Jackson, DirectorStateRecord.class)
                        .bean(projectMemoryStore, "applyDirectorState")
                .end();
    }

    private static String combineScratchpad(String existingScratchpad, String latestNote) {
        String existing = normalize(existingScratchpad);
        String latest = normalize(latestNote);
        if (latest.isBlank()) {
            return existing;
        }
        if (existing.isBlank()) {
            return latest;
        }
        if (existing.startsWith(latest)) {
            return existing;
        }
        return clamp(latest + "\n\n" + existing);
    }

    private static String clamp(String value) {
        if (value.length() <= SCRATCHPAD_LIMIT) {
            return value;
        }
        return value.substring(0, SCRATCHPAD_LIMIT - 1).trim() + "…";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
