package us.nusun.llmshare.framework.tests.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.mcp.client.*;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.logging.LogTokens;
import us.nusun.llmshare.framework.inventory.ToolProfileRegistry;
import us.nusun.llmshare.framework.mcp.ProfiledMcpClient;
import us.nusun.llmshare.framework.model.McpServerDefinition;
import us.nusun.llmshare.framework.model.Persona;
import us.nusun.llmshare.framework.model.ToolProfile;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolProfileSemanticsTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void configuredDefaultArgumentsAreAppliedBeforeToolExecution() throws Exception {
        ToolProfile profile = FrameworkFixtures.toolProfileRegistry().getRequired("project-management");
        ProfiledMcpClient client = new ProfiledMcpClient(null, "plane", profile);

        ToolExecutionRequest request = ToolExecutionRequest.builder().id("1").name("list_projects").arguments("{}").build();

        JsonNode arguments = OBJECT_MAPPER.readTree(client.applyProfile(request).arguments());

        assertEquals("id,name,identifier", arguments.get("fields").asText());
    }

    @Test
    void jsonDefaultArgumentsSupportScalarsAndArrays() throws Exception {
        ToolProfile profile = ToolProfile.builder("json-defaults")
                .server("plane")
                .defaultArgument("plane", "search", "includeClosed", true)
                .defaultArgument("plane", "search", "limit", 5)
                .defaultArgument("plane", "search", "query", "parking")
                .defaultArgumentJson("plane", "search", "states", "[\"open\",\"started\"]")
                .build();
        ProfiledMcpClient client = new ProfiledMcpClient(null, "plane", profile);

        ToolExecutionRequest request = ToolExecutionRequest.builder().id("1").name("search").arguments("{}").build();

        JsonNode arguments = OBJECT_MAPPER.readTree(client.applyProfile(request).arguments());
        assertTrue(arguments.get("includeClosed").asBoolean());
        assertEquals(5, arguments.get("limit").asInt());
        assertEquals("parking", arguments.get("query").asText());
        assertEquals("open", arguments.get("states").get(0).asText());
    }

    @Test
    void serverDeclaresLogicalToolSurfaceButMcpServerDefinesBuildableClient() {
        ToolProfile logicalOnly = ToolProfile.builder("logical-only")
                .server("gitea")
                .build();
        ToolProfile buildable = ToolProfile.builder("buildable")
                .mcpServer(McpServerDefinition.builder("gitea").url("http://gitea.example/mcp").build())
                .build();

        assertTrue(logicalOnly.allowsServer("gitea"));
        assertTrue(logicalOnly.mcpServers().isEmpty());
        assertTrue(buildable.allowsServer("gitea"));
        assertEquals("http://gitea.example/mcp", buildable.mcpServers().get("gitea").url());
    }

    @Test
    void profileDefaultsDoNotOverrideCallerProvidedArguments() throws Exception {
        ToolProfile profile = FrameworkFixtures.toolProfileRegistry().getRequired("project-management");
        ProfiledMcpClient client = new ProfiledMcpClient(null, "plane", profile);

        ToolExecutionRequest request = ToolExecutionRequest.builder().id("1").name("list_projects").arguments("{\"fields\":\"id,name\",\"limit\":5}").build();

        JsonNode arguments = OBJECT_MAPPER.readTree(client.applyProfile(request).arguments());

        assertEquals("id,name", arguments.get("fields").asText());
        assertEquals(5, arguments.get("limit").asInt());
    }

    @Test
    void listToolsOnlyExposesAllowedTools() {
        ToolProfile profile = ToolProfile.builder("review-only")
                .server("source-control")
                .allowTool("source-control", "get_pull_request")
                .build();
        ProfiledMcpClient client = new ProfiledMcpClient(new FakeMcpClient(), "source-control", profile);

        assertEquals(List.of("get_pull_request"), client.listTools().stream().map(ToolSpecification::name).toList());
    }

    @Test
    void serverWithoutAllowedToolsExposesServerToolsByDefault() {
        ToolProfile profile = ToolProfile.builder("low-config")
                .server("source-control")
                .build();
        ProfiledMcpClient client = new ProfiledMcpClient(new FakeMcpClient(), "source-control", profile);

        assertEquals(List.of("get_pull_request", "delete_repository"), client.listTools().stream().map(ToolSpecification::name).toList());
        assertTrue(profile.allowsTool("source-control", "get_pull_request"));
    }

    @Test
    void denyListRemovesToolsWithoutRequiringACompleteAllowList() {
        ToolProfile profile = ToolProfile.builder("safe-default")
                .server("source-control")
                .denyTool("source-control", "delete_repository")
                .build();
        ProfiledMcpClient client = new ProfiledMcpClient(new FakeMcpClient(), "source-control", profile);

        assertEquals(List.of("get_pull_request"), client.listTools().stream().map(ToolSpecification::name).toList());
        assertTrue(profile.allowsTool("source-control", "get_pull_request"));
        assertFalse(profile.allowsTool("source-control", "delete_repository"));
    }

    @Test
    void undeclaredServerExposesNoTools() {
        ToolProfile profile = ToolProfile.builder("other-server")
                .server("project-management")
                .build();
        ProfiledMcpClient client = new ProfiledMcpClient(new FakeMcpClient(), "source-control", profile);

        assertTrue(client.listTools().isEmpty());
        assertFalse(profile.allowsTool("source-control", "get_pull_request"));
    }

    @Test
    void profileRejectsToolsOutsideTheDeclaredToolSurface() {
        ToolProfile profile = ToolProfile.builder("safe-default")
                .server("source-control")
                .denyTool("source-control", "delete_repository")
                .build();
        ProfiledMcpClient client = new ProfiledMcpClient(new FakeMcpClient(), "source-control", profile);
        ToolExecutionRequest request = ToolExecutionRequest.builder().id("1").name("delete_repository").arguments("{}").build();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> client.executeTool(request));

        assertEquals("Tool delete_repository is not allowed by profile safe-default", failure.getMessage());
    }

    @Test
    void toolResultsAreLimitedByActiveProfile() {
        ToolProfile profile = ToolProfile.builder("tiny")
                .server("source-control")
                .allowTool("source-control", "get_pull_request")
                .maxResultCharacters(24)
                .build();
        ProfiledMcpClient client = new ProfiledMcpClient(new FakeMcpClient(), "source-control", profile);

        ToolExecutionResult result = client.executeTool(ToolExecutionRequest.builder().id("1").name("get_pull_request").arguments("{}").build());

        assertEquals("...(truncated, original ", result.resultText());
        assertTrue(result.resultText().length() <= 24);
    }

    @Test
    void runtimePathAppliesGlobalLimitWhenProfileDoesNotOverrideIt() {
        ToolProfile profile = ToolProfile.builder("global-limited")
                .server("source-control")
                .allowTool("source-control", "get_pull_request")
                .build();
        ProfiledMcpClient client = new ProfiledMcpClient(new FakeMcpClient("abcdefghijklmnopqrstuvwxyz"), "source-control", profile, null, null, 20);

        ToolExecutionResult result = client.executeTool(ToolExecutionRequest.builder().id("1").name("get_pull_request").arguments("{}").build());

        assertEquals("...(truncated, origi", result.resultText());
    }

    @Test
    void profileSpecificLimitOverridesGlobalDefault() {
        String longResult = "x".repeat(1500);
        ToolProfile profile = ToolProfile.builder("large")
                .server("source-control")
                .allowTool("source-control", "get_pull_request")
                .maxResultCharacters(5000)
                .build();
        ProfiledMcpClient client = new ProfiledMcpClient(new FakeMcpClient(longResult), "source-control", profile, null, null, 1000);

        ToolExecutionResult result = client.executeTool(ToolExecutionRequest.builder().id("1").name("get_pull_request").arguments("{}").build());

        assertEquals(1500, result.resultText().length());
        assertEquals(longResult, result.resultText());
    }

    @Test
    void disabledGlobalLimitAllowsUnlimitedResultsWhenProfileDoesNotSetLimit() {
        String longResult = "x".repeat(1500);
        ToolProfile profile = ToolProfile.builder("unlimited")
                .server("source-control")
                .allowTool("source-control", "get_pull_request")
                .build();
        ProfiledMcpClient client = new ProfiledMcpClient(new FakeMcpClient(longResult), "source-control", profile, null, null, 0);

        ToolExecutionResult result = client.executeTool(ToolExecutionRequest.builder().id("1").name("get_pull_request").arguments("{}").build());

        assertEquals(longResult, result.resultText());
    }

    @Test
    void dimmedToolResponseLogUsesLimitedResultText() {
        String hugeResult = "abcdefghijklmnopqrstuvwxyz0123456789";
        ToolProfile profile = ToolProfile.builder("logged")
                .server("source-control")
                .allowTool("source-control", "get_pull_request")
                .build();
        CapturingLogTokens logs = new CapturingLogTokens();
        Persona persona = FrameworkFixtures.personaCatalog().getRequired(FrameworkFixtures.PERSONA_A);
        ProfiledMcpClient client = new ProfiledMcpClient(new FakeMcpClient(hugeResult), "source-control", profile, persona, logs, 20);

        ToolExecutionResult result = client.executeTool(ToolExecutionRequest.builder().id("1").name("get_pull_request").arguments("{}").build());

        assertEquals("...(truncated, origi", result.resultText());
        assertTrue(logs.messages.stream().anyMatch(message -> message.contains("RESPONSE")));
        assertFalse(String.join("\n", logs.messages).contains(hugeResult));
        assertTrue(String.join("\n", logs.messages).contains("...(truncated"));
    }

    @Test
    void genericToolProfilesDeclareToolSurfaceWithoutForcingScenarioSpecificProfiles() {
        ToolProfile profile = FrameworkFixtures.toolProfileRegistry().getRequired("project-management");

        assertTrue(profile.allowsServer("plane"));
        assertTrue(profile.allowsTool("plane", "list_projects"));
        assertTrue(profile.allowsTool("plane", "create_issue"));
        assertFalse(profile.allowsTool("plane", "delete_project"));
        assertEquals("id,name,identifier", profile.defaultArguments("plane", "list_projects").get("fields").asText());
    }

    @Test
    void noneProfileIsFrameworkBuiltInAndExcludedFromCatalogListings() {
        ToolProfileRegistry registry = new ToolProfileRegistry(List.of(ToolProfile.builder("tools").server("gitea").build()));

        assertTrue(registry.getRequired(null).none());
        assertTrue(registry.getRequired("").none());
        assertTrue(registry.getRequired("none").none());
        assertEquals(List.of("tools"), registry.all().stream().map(ToolProfile::id).toList());
        assertFalse(registry.allJson().contains("\"id\":\"none\""));
    }

    @Test
    void userDefinedNoneProfileIsRejectedAsReservedFrameworkId() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new ToolProfileRegistry(List.of(ToolProfile.builder("none").build())));

        assertEquals("Tool profile id 'none' is reserved by the framework", failure.getMessage());
    }

    static class FakeMcpClient implements McpClient {
        private final String resultText;

        FakeMcpClient() {
            this("abcdefghijklmnopqrstuvwxyz0123456789");
        }

        FakeMcpClient(String resultText) {
            this.resultText = resultText;
        }

        @Override public String key() { return "fake"; }
        @Override public List<ToolSpecification> listTools() {
            return List.of(
                    ToolSpecification.builder().name("get_pull_request").description("Get PR").build(),
                    ToolSpecification.builder().name("delete_repository").description("Delete repo").build());
        }
        @Override public List<ToolSpecification> listTools(InvocationContext invocationContext) { return listTools(); }
        @Override public ToolExecutionResult executeTool(ToolExecutionRequest executionRequest) { return ToolExecutionResult.builder().isError(false).resultText(resultText).build(); }
        @Override public ToolExecutionResult executeTool(ToolExecutionRequest executionRequest, InvocationContext invocationContext) { return executeTool(executionRequest); }
        @Override public List<McpResource> listResources() { return List.of(); }
        @Override public List<McpResource> listResources(InvocationContext invocationContext) { return List.of(); }
        @Override public List<McpResourceTemplate> listResourceTemplates() { return List.of(); }
        @Override public List<McpResourceTemplate> listResourceTemplates(InvocationContext invocationContext) { return List.of(); }
        @Override public McpReadResourceResult readResource(String uri) { return null; }
        @Override public McpReadResourceResult readResource(String uri, InvocationContext invocationContext) { return null; }
        @Override public void subscribeToResource(String uri) {}
        @Override public void unsubscribeFromResource(String uri) {}
        @Override public List<McpPrompt> listPrompts() { return List.of(); }
        @Override public McpGetPromptResult getPrompt(String name, Map<String, Object> arguments) { return null; }
        @Override public void checkHealth() {}
        @Override public void setRoots(List<McpRoot> roots) {}
        @Override public void close() {}
    }

    private static final class CapturingLogTokens extends LogTokens {
        private final List<String> messages = new ArrayList<>();

        private CapturingLogTokens() {
            super(false);
        }

        @Override
        public void print(String message) {
            messages.add(message);
        }
    }
}
