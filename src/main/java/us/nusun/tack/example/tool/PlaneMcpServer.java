package us.nusun.tack.example.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.tack.framework.tool.McpServerDefinition;

import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class PlaneMcpServer {
    public static final String ID = "plane";

    private static final Map<String, Set<String>> READ_ACTIONS = Map.of(
            "project", Set.of("list", "retrieve"),
            "member", Set.of("me", "list_workspace", "list_project"),
            "workitem", Set.of("list", "retrieve", "retrieve_by_identifier", "search"),
            "workitem_activity", Set.of("list"),
            "workitem_comment", Set.of("list", "retrieve"),
            "label", Set.of("list"),
            "state", Set.of("list"));

    private final McpServerDefinition definition;
    private final ObjectReader readArguments;

    @Inject
    public PlaneMcpServer(
            ObjectMapper mapper,
            @ConfigProperty(name = "agent.tool.mcp.plane.url", defaultValue = "") String url,
            @ConfigProperty(name = "agent.tool.mcp.plane.workspace-slug", defaultValue = "") String workspaceSlug) {
        McpServerDefinition.Builder plane = McpServerDefinition.builder(ID)
                .url(url)
                .transportType("streamableHttp")
                .authHeaderName("Authorization")
                .authHeaderValueFormat("Bearer %s");
        if (workspaceSlug != null && !workspaceSlug.isBlank()) {
            plane.extraHeader("x-workspace-slug", workspaceSlug);
        }
        this.definition = plane.build();
        this.readArguments = mapper.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public McpServerDefinition definition() {
        return definition;
    }

    void requireReadAction(String tool, String arguments) {
        String action;
        try {
            var input = arguments == null ? null : readArguments.readTree(arguments);
            action = input == null ? null : input.path("action").textValue();
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Invalid JSON arguments for read-only Plane tool " + tool, failure);
        }
        Set<String> allowed = READ_ACTIONS.getOrDefault(tool, Set.of());
        if (action == null || !allowed.contains(action)) {
            throw new IllegalArgumentException("Read-only Plane tool " + tool + " requires an explicit action from " + allowed);
        }
    }
}
