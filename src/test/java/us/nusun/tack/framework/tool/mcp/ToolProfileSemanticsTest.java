package us.nusun.tack.framework.tool.mcp;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.mcp.client.*;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.exception.ToolExecutionException;
import org.junit.jupiter.api.Test;
import us.nusun.tack.framework.tool.ToolProfileRegistry;
import us.nusun.tack.framework.logging.LogTokens;
import dev.langchain4j.service.tool.ToolProviderRequest;
import us.nusun.tack.framework.tool.McpServerDefinition;
import us.nusun.tack.framework.persona.Persona;
import us.nusun.tack.framework.tool.ToolProfile;
import us.nusun.tack.framework.tests.support.FrameworkFixtures;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolProfileSemanticsTest {
    private static final ToolProviderRequest DISCOVERY =
            new ToolProviderRequest("test-memory", UserMessage.from("List available tools"));

    private static ToolProvider policy(McpClient client, String server, ToolProfile profile) {
        return McpToolPolicy.provider(client, server, profile, null, null);
    }

    private static List<ToolSpecification> tools(ToolProvider policy) {
        return policy.provideTools(DISCOVERY).aiServiceTools().stream().map(tool -> tool.toolSpecification()).toList();
    }

    @Test
    void promptGuidanceIsAvailableFromBuilderAndSyntheticProfiles() {
        ToolProfile custom = ToolProfile.builder("guided")
                .promptGuidance("Read first; avoid duplicates.")
                .build();

        assertEquals("Read first; avoid duplicates.", custom.promptGuidance());
        assertTrue(FrameworkFixtures.toolProfileRegistry().getRequired(FrameworkFixtures.PROFILE_GAMMA).promptGuidance().contains("Read before writing"));
        assertTrue(FrameworkFixtures.toolProfileRegistry().getRequired(FrameworkFixtures.PROFILE_BETA).promptGuidance().contains("Find existing synthetic work"));
        assertTrue(FrameworkFixtures.toolProfileRegistry().getRequired(FrameworkFixtures.PROFILE_ALPHA).promptGuidance().contains("Read recent synthetic context"));
    }

    @Test
    void singleToolProfileMayContainMultipleMcpServerDefinitions() {
        ToolProfile profile = ToolProfile.builder("multi-server")
                .mcpServer(McpServerDefinition.builder(FrameworkFixtures.SERVER_ALPHA).url("http://alpha.example/mcp").build())
                .mcpServer(McpServerDefinition.builder(FrameworkFixtures.SERVER_BETA).url("http://beta.example/mcp").build())
                .allowAllTools(FrameworkFixtures.SERVER_ALPHA)
                .allowAllTools(FrameworkFixtures.SERVER_BETA)
                .build();

        assertEquals(2, profile.policies().size());
        assertTrue(profile.policies().containsKey(FrameworkFixtures.SERVER_ALPHA));
        assertTrue(profile.policies().containsKey(FrameworkFixtures.SERVER_BETA));
    }

    @Test
    void nativeSchemaAndCompletePayloadSurviveNamespacedDispatch() {
        ToolProfile profile = ToolProfile.builder("composite")
                .mcpServer(McpServerDefinition.builder(FrameworkFixtures.SERVER_ALPHA).build())
                .mcpServer(McpServerDefinition.builder(FrameworkFixtures.SERVER_BETA).build())
                .allowTool(FrameworkFixtures.SERVER_ALPHA, "search")
                .allowTool(FrameworkFixtures.SERVER_BETA, "search")
                .build();
        var schema = JsonObjectSchema.builder().description("Original request")
                .addProperty("action", JsonEnumSchema.builder().enumValues("search", "update").build())
                .addStringProperty("pql").required("action").additionalProperties(false).build();
        var spec = ToolSpecification.builder().name("search").description("Original description").parameters(schema).build();
        String payload = "{\"rows\":[\"" + "large-result".repeat(6000) + "\"],\"complete\":true}";
        var completeResult = ToolExecutionResult.builder().resultText(payload)
                .attributes(Map.of("cursor", "last")).build();
        var alpha = new FakeMcpClient(completeResult, List.of(spec));
        var beta = new FakeMcpClient(List.of(spec));
        var exposed = policy(alpha, FrameworkFixtures.SERVER_ALPHA, profile).provideTools(DISCOVERY);
        var advertised = exposed.toolSpecificationByName("alpha_mcp__search");
        assertEquals(spec.toBuilder().name("alpha_mcp__search").build(), advertised);
        assertSame(schema, advertised.parameters());
        assertEquals(List.of("beta_mcp__search"), tools(policy(beta, FrameworkFixtures.SERVER_BETA, profile))
                .stream().map(ToolSpecification::name).toList());

        var request = ToolExecutionRequest.builder().id("call-1").name(advertised.name())
                .arguments(" { \"action\": \"search\", \"pql\": \"priority = high\", \"extra\": null } ").build();
        var context = InvocationContext.builder().chatMemoryId("run-1").build();
        var result = exposed.toolExecutorByName(advertised.name()).executeWithContext(request, context);

        assertEquals(request.toBuilder().name(spec.name()).build(), alpha.lastRequest);
        assertSame(context, alpha.lastContext);
        assertSame(completeResult, result);
        assertEquals(payload, result.resultText());
        assertNull(beta.lastRequest);
    }

    @Test
    void executionCheckRejectsBeforeDispatchAndKeepsSuccessfulArgumentsUnchanged() {
        var spec = ToolSpecification.builder().name("lookup").build();
        var client = new FakeMcpClient(List.of(spec));
        var profile = ToolProfile.builder("restricted")
                .mcpServer(McpServerDefinition.builder("catalog").build())
                .allowTool("catalog", "lookup")
                .denyTool("catalog", "delete")
                .authorizeMcpExecution("catalog", (tool, raw) -> {
                    assertEquals("lookup", tool);
                    if (!"read only".equals(raw)) {
                        throw new IllegalArgumentException("Read access only");
                    }
                }).build();
        var exposed = policy(client, "catalog", profile).provideTools(DISCOVERY);
        var executor = exposed.toolExecutorByName("lookup");
        var request = ToolExecutionRequest.builder().name("lookup").arguments("write").build();

        assertThrows(IllegalArgumentException.class, () -> executor.execute(request, "memory"));
        assertThrows(IllegalArgumentException.class, () -> executor.execute(request.toBuilder().name("delete").build(), "memory"));
        assertNull(client.lastRequest);
        var read = request.toBuilder().arguments("read only").build();
        executor.execute(read, "memory");
        assertEquals(read, client.lastRequest);
    }

    @Test
    void serverErrorPayloadAndExceptionsKeepTheirSemantics() {
        var profile = ToolProfile.builder("limited").allowTool("catalog", "lookup").build();
        var spec = ToolSpecification.builder().name("lookup").build();
        var error = ToolExecutionResult.builder().isError(true).resultText("error-detail".repeat(5000)).build();
        var client = new FakeMcpClient(error, List.of(spec));
        var logs = new CapturingLogTokens();
        var persona = FrameworkFixtures.personaCatalog().getRequired(FrameworkFixtures.PERSONA_A);
        var executor = McpToolPolicy.provider(client, "catalog", profile, persona, logs)
                .provideTools(DISCOVERY).toolExecutorByName("lookup");
        var request = ToolExecutionRequest.builder().name("lookup").arguments("{}").build();
        var context = InvocationContext.builder().chatMemoryId("test").build();

        assertSame(error, executor.executeWithContext(request, context));
        assertTrue(logs.messages.stream().anyMatch(message -> message.contains("RESPONSE isError=true")));
        var failure = new ToolExecutionException("MCP protocol error");
        client.failure = failure;
        assertSame(failure, assertThrows(ToolExecutionException.class, () -> executor.executeWithContext(request, context)));
    }

    @Test
    void singleServerProfileKeepsOriginalToolNames() {
        ToolProfile profile = ToolProfile.builder("single-server")
                .mcpServer(McpServerDefinition.builder(FrameworkFixtures.SERVER_ALPHA).url("http://alpha.example/mcp").build())
                .allowTool(FrameworkFixtures.SERVER_ALPHA, "get_me")
                .build();
        ToolProvider client = policy(
                new FakeMcpClient(List.of(ToolSpecification.builder().name("get_me").description("Current user").build())),
                FrameworkFixtures.SERVER_ALPHA,
                profile);

        assertEquals(List.of("get_me"), tools(client).stream().map(ToolSpecification::name).toList());
    }

    @Test
    void serverDeclaresLogicalToolSurfaceButMcpServerDefinesBuildableClient() {
        ToolProfile logicalOnly = ToolProfile.builder("logical-only")
                .server(FrameworkFixtures.SERVER_GAMMA)
                .build();
        ToolProfile buildable = ToolProfile.builder("buildable")
                .mcpServer(McpServerDefinition.builder(FrameworkFixtures.SERVER_GAMMA).url("http://gamma.example/mcp").build())
                .build();

        assertTrue(logicalOnly.policies().containsKey(FrameworkFixtures.SERVER_GAMMA));
        assertNull(logicalOnly.policies().get(FrameworkFixtures.SERVER_GAMMA).connection());
        assertTrue(buildable.policies().containsKey(FrameworkFixtures.SERVER_GAMMA));
        assertEquals("http://gamma.example/mcp", buildable.policies().get(FrameworkFixtures.SERVER_GAMMA).connection().url());
    }

    @Test
    void allowListsAreAppliedIndependentlyPerServer() {
        ToolProfile profile = ToolProfile.builder("split-allow")
                .server(FrameworkFixtures.SERVER_ALPHA)
                .server(FrameworkFixtures.SERVER_BETA)
                .allowTool(FrameworkFixtures.SERVER_ALPHA, "alpha_read")
                .allowTool(FrameworkFixtures.SERVER_BETA, "beta_read")
                .build();

        assertEquals(List.of("alpha_read"), tools(policy(new FakeMcpClient(), FrameworkFixtures.SERVER_ALPHA, profile)).stream().map(ToolSpecification::name).toList());
        assertEquals(List.of("beta_read"), tools(policy(new FakeMcpClient(), FrameworkFixtures.SERVER_BETA, profile)).stream().map(ToolSpecification::name).toList());
    }

    @Test
    void explicitAllowListExposesOnlyListedTools() {
        ToolProfile profile = ToolProfile.builder("review-only")
                .server(FrameworkFixtures.SERVER_GAMMA)
                .allowTool(FrameworkFixtures.SERVER_GAMMA, "gamma_get_pull_request")
                .build();
        ToolProvider client = policy(new FakeMcpClient(), FrameworkFixtures.SERVER_GAMMA, profile);

        assertEquals(List.of("gamma_get_pull_request"), tools(client).stream().map(ToolSpecification::name).toList());
    }

    @Test
    void broadProfileWithoutAllowListExposesToolsExceptDeniedOnes() {
        ToolProfile profile = ToolProfile.builder("broad")
                .server(FrameworkFixtures.SERVER_GAMMA)
                .denyTool(FrameworkFixtures.SERVER_GAMMA, "delete_repository")
                .build();
        ToolProvider client = policy(new FakeMcpClient(), FrameworkFixtures.SERVER_GAMMA, profile);

        assertEquals(List.of("alpha_read", "alpha_write", "alpha_delete", "beta_read", "gamma_get_pull_request"), tools(client).stream().map(ToolSpecification::name).toList());
        assertTrue(profile.allowsTool(FrameworkFixtures.SERVER_GAMMA, "gamma_get_pull_request"));
        assertFalse(profile.allowsTool(FrameworkFixtures.SERVER_GAMMA, "delete_repository"));
    }

    @Test
    void denyRulesTakePrecedenceOverAllowRules() {
        ToolProfile profile = ToolProfile.builder("deny-wins")
                .server(FrameworkFixtures.SERVER_ALPHA)
                .allowTool(FrameworkFixtures.SERVER_ALPHA, "alpha_delete")
                .denyTool(FrameworkFixtures.SERVER_ALPHA, "alpha_delete")
                .build();

        assertFalse(profile.allowsTool(FrameworkFixtures.SERVER_ALPHA, "alpha_delete"));
        assertTrue(tools(policy(new FakeMcpClient(), FrameworkFixtures.SERVER_ALPHA, profile)).isEmpty());
    }

    @Test
    void undeclaredServerExposesNoTools() {
        ToolProfile profile = ToolProfile.builder("other-server")
                .server(FrameworkFixtures.SERVER_BETA)
                .build();
        ToolProvider client = policy(new FakeMcpClient(), FrameworkFixtures.SERVER_ALPHA, profile);

        assertTrue(tools(client).isEmpty());
        assertFalse(profile.allowsTool(FrameworkFixtures.SERVER_ALPHA, "alpha_read"));
    }

    @Test
    void reusedImmutableServerDefinitionsBehaveCorrectlyAcrossProfiles() {
        McpServerDefinition shared = McpServerDefinition.builder(FrameworkFixtures.SERVER_ALPHA)
                .url("http://alpha.example/mcp")
                .extraHeader("x-synthetic", "one")
                .build();
        ToolProfile first = ToolProfile.builder("first")
                .mcpServer(shared)
                .allowTool(FrameworkFixtures.SERVER_ALPHA, "alpha_read")
                .build();
        ToolProfile second = ToolProfile.builder("second")
                .mcpServer(shared)
                .denyTool(FrameworkFixtures.SERVER_ALPHA, "alpha_delete")
                .build();

        assertEquals("http://alpha.example/mcp", first.policies().get(FrameworkFixtures.SERVER_ALPHA).connection().url());
        assertEquals("one", second.policies().get(FrameworkFixtures.SERVER_ALPHA).connection().extraHeaders().get("x-synthetic"));
        assertTrue(first.allowsTool(FrameworkFixtures.SERVER_ALPHA, "alpha_read"));
        assertFalse(second.allowsTool(FrameworkFixtures.SERVER_ALPHA, "alpha_delete"));
        assertThrows(UnsupportedOperationException.class, () -> shared.extraHeaders().put("x-other", "two"));
    }

    @Test
    void genericToolProfilesDeclareToolSurfaceWithoutScenarioSpecificProfiles() {
        ToolProfile profile = FrameworkFixtures.toolProfileRegistry().getRequired(FrameworkFixtures.PROFILE_BETA);

        assertTrue(profile.policies().containsKey(FrameworkFixtures.SERVER_BETA));
        assertTrue(profile.allowsTool(FrameworkFixtures.SERVER_BETA, "beta_list"));
        assertTrue(profile.allowsTool(FrameworkFixtures.SERVER_BETA, "beta_create"));
        assertFalse(profile.allowsTool(FrameworkFixtures.SERVER_BETA, "beta_delete"));
    }

    @Test
    void noneProfileIsFrameworkBuiltInAndExcludedFromCatalogListings() {
        ToolProfileRegistry registry = new ToolProfileRegistry(List.of(ToolProfile.builder("tools").server(FrameworkFixtures.SERVER_GAMMA).build()));

        assertTrue(registry.getRequired(null).none());
        assertTrue(registry.getRequired("").none());
        assertTrue(registry.getRequired("none").none());
        assertEquals(List.of("tools"), registry.all().stream().map(ToolProfile::id).toList());
    }

    @Test
    void userDefinedNoneProfileIsRejectedAsReservedFrameworkId() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new ToolProfileRegistry(List.of(ToolProfile.builder("none").build())));

        assertEquals("Tool profile id 'none' is reserved by the framework", failure.getMessage());
    }

    static class FakeMcpClient implements McpClient {
        private final ToolExecutionResult result;
        private final List<ToolSpecification> tools;
        private ToolExecutionRequest lastRequest;
        private InvocationContext lastContext;
        private RuntimeException failure;

        FakeMcpClient() {
            this(defaultTools());
        }

        FakeMcpClient(List<ToolSpecification> tools) {
            this(ToolExecutionResult.builder().resultText("ok").build(), tools);
        }

        FakeMcpClient(ToolExecutionResult result, List<ToolSpecification> tools) {
            this.result = result;
            this.tools = tools;
        }

        @Override public String key() { return "fake"; }
        @Override public List<ToolSpecification> listTools() { return tools; }
        @Override public List<ToolSpecification> listTools(InvocationContext context) { return listTools(); }
        @Override public ToolExecutionResult executeTool(ToolExecutionRequest request) { return executeTool(request, null); }

        @Override
        public ToolExecutionResult executeTool(ToolExecutionRequest request, InvocationContext context) {
            lastRequest = request;
            lastContext = context;
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        private static List<ToolSpecification> defaultTools() {
            return List.of(
                    ToolSpecification.builder().name("alpha_read").description("Read alpha").build(),
                    ToolSpecification.builder().name("alpha_write").description("Write alpha").build(),
                    ToolSpecification.builder().name("alpha_delete").description("Delete alpha").build(),
                    ToolSpecification.builder().name("beta_read").description("Read beta").build(),
                    ToolSpecification.builder().name("gamma_get_pull_request").description("Get review item").build(),
                    ToolSpecification.builder().name("delete_repository").description("Delete repository").build());
        }
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
