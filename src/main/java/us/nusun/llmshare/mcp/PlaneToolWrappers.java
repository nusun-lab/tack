package us.nusun.llmshare.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;

public final class PlaneToolWrappers {
    static final String LIST_PROJECTS_TOOL_NAME = "list_projects";
    static final String LIST_WORK_ITEMS_TOOL_NAME = "list_work_items";
    static final String MINIMAL_PROJECT_FIELDS = "id,name,identifier";
    static final String MINIMAL_WORK_ITEM_FIELDS = "id,name,sequence_id,state,priority";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private PlaneToolWrappers() {
    }

    public static ToolExecutor addMinimalProjectFields(ToolExecutor delegate) {
        return new ToolExecutor() {
            @Override
            public String execute(ToolExecutionRequest executionRequest, Object memoryId) {
                return delegate.execute(patchListProjectsRequest(executionRequest), memoryId);
            }

            @Override
            public dev.langchain4j.service.tool.ToolExecutionResult executeWithContext(
                    ToolExecutionRequest executionRequest,
                    dev.langchain4j.invocation.InvocationContext invocationContext) {
                return delegate.executeWithContext(patchListProjectsRequest(executionRequest), invocationContext);
            }
        };
    }

    static ToolExecutionRequest patchListProjectsRequest(ToolExecutionRequest request) {
        String forcedFields = forcedFieldsFor(request.name());
        if (forcedFields == null) {
            return request;
        }

        ObjectNode arguments = parseArgumentsObject(request.arguments());
        arguments.put("fields", forcedFields);

        return request.toBuilder()
                .arguments(arguments.toString())
                .build();
    }

    private static String forcedFieldsFor(String toolName) {
        if (LIST_PROJECTS_TOOL_NAME.equals(toolName)) {
            return MINIMAL_PROJECT_FIELDS;
        }
        if (LIST_WORK_ITEMS_TOOL_NAME.equals(toolName)) {
            return MINIMAL_WORK_ITEM_FIELDS;
        }
        return null;
    }

    private static ObjectNode parseArgumentsObject(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return OBJECT_MAPPER.createObjectNode();
        }
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(arguments);
            return parsed instanceof ObjectNode objectNode ? objectNode : OBJECT_MAPPER.createObjectNode();
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to parse Plane MCP tool arguments as JSON", e);
        }
    }
}
