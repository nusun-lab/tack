package us.nusun.tack.framework.tests.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.mcp.client.*;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import us.nusun.tack.example.tool.MattermostMcpServer;
import us.nusun.tack.example.tool.GiteaMcpServer;
import us.nusun.tack.example.tool.PlaneMcpServer;
import us.nusun.tack.example.tool.WorkspaceReadonlyToolProfile;
import us.nusun.tack.framework.inventory.ToolProfileRegistry;
import us.nusun.tack.framework.logging.LogTokens;
import us.nusun.tack.framework.mcp.McpToolPolicy;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.model.TokenCountEstimator;
import us.nusun.tack.framework.model.McpServerDefinition;
import us.nusun.tack.framework.model.Persona;
import us.nusun.tack.framework.model.ToolProfile;
import us.nusun.tack.framework.tests.support.FrameworkFixtures;
import us.nusun.tack.framework.tests.support.TestTokenCountEstimator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolProfileSemanticsTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TestTokenCountEstimator TOKEN_ESTIMATOR = new TestTokenCountEstimator();

    private static McpToolPolicy policy(McpClient client, String server, ToolProfile profile) {
        return policy(client, server, profile, null, null, 0, null);
    }

    private static McpToolPolicy policy(McpClient client, String server, ToolProfile profile,
                                        Persona persona, LogTokens logs, int maximum) {
        return policy(client, server, profile, persona, logs, maximum, null);
    }

    private static McpToolPolicy policy(McpClient client, String server, ToolProfile profile,
                                        Persona persona, LogTokens logs, int maximum, TokenCountEstimator estimator) {
        return new McpToolPolicy(client, server, profile, persona, logs, maximum, estimator);
    }

    private static List<ToolSpecification> tools(McpToolPolicy policy) {
        return List.copyOf(policy.provider().provideTools(new ToolProviderRequest("test-memory", UserMessage.from("List available tools"))).tools().keySet());
    }

    private static ToolExecutionResult execute(McpToolPolicy policy, ToolExecutionRequest request) {
        return policy.provider().provideTools(new ToolProviderRequest("test-memory", UserMessage.from("List available tools")))
                .toolExecutorByName(request.name()).executeWithContext(request, InvocationContext.builder().userMessage(UserMessage.from("List available tools")).build());
    }

    @Test
    void readOnlyPlaneToolsRejectMutationsBeforeDispatch() {
        var readOnly = new WorkspaceReadonlyToolProfile(
                new PlaneMcpServer("", ""),
                new GiteaMcpServer(""),
                new MattermostMcpServer(""),
                4000).toolProfiles().getFirst();
        var delegate = new FakeMcpClient(List.of(ToolSpecification.builder().name("workitem").build()));
        var policy = policy(delegate, "plane", readOnly, null, null, 0, TOKEN_ESTIMATOR);
        var parameters = tools(policy).getFirst().parameters();
        var action = assertInstanceOf(JsonEnumSchema.class, parameters.properties().get("action"));
        assertEquals(List.of("list", "retrieve", "retrieve_by_identifier", "search"), action.enumValues());
        assertTrue(parameters.required().contains("action"));

        for (String arguments : List.of("{\"action\":\"update\"}", "{\"action\":null}", "{}")) {
            var request = ToolExecutionRequest.builder().name("plane__workitem").arguments(arguments).build();
            assertThrows(IllegalArgumentException.class, () -> execute(policy, request));
            assertNull(delegate.lastRequest);
        }
        execute(policy, ToolExecutionRequest.builder().name("plane__workitem")
                .arguments("{\"action\":\"list\"}").build());
        assertEquals("workitem", delegate.lastToolName());
    }

    @Test
    void permittedValuesAreCheckedAfterApplyingProfileDefaults() throws Exception {
        var profile = ToolProfile.builder("read")
                .allowTool("plane", "workitem")
                .permitArgumentValues("plane", "workitem", "action", "list")
                .defaultArgument("plane", "workitem", "action", "list")
                .build();
        var delegate = new FakeMcpClient(List.of(ToolSpecification.builder().name("workitem").build()));
        var policy = policy(delegate, "plane", profile);

        assertFalse(tools(policy).getFirst().parameters().required().contains("action"));
        execute(policy, ToolExecutionRequest.builder().name("workitem").arguments("{}").build());
        assertEquals("list", OBJECT_MAPPER.readTree(delegate.lastRequest.arguments()).get("action").asText());
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

        assertEquals(2, profile.mcpServers().size());
        assertTrue(profile.allowsServer(FrameworkFixtures.SERVER_ALPHA));
        assertTrue(profile.allowsServer(FrameworkFixtures.SERVER_BETA));
    }

    @Test
    void configuredDefaultArgumentsAreAppliedBeforeToolExecution() throws Exception {
        ToolProfile profile = FrameworkFixtures.toolProfileRegistry().getRequired(FrameworkFixtures.PROFILE_BETA);
        McpToolPolicy client = policy(null, FrameworkFixtures.SERVER_BETA, profile);

        ToolExecutionRequest request = ToolExecutionRequest.builder().id("1").name("beta_list").arguments("{}").build();

        JsonNode arguments = OBJECT_MAPPER.readTree(client.prepareRequest(request).arguments());

        assertEquals("id,name,identifier", arguments.get("fields").asText());
    }

    @Test
    void hiddenArgumentIsRemovedFromAdvertisedToolSchema() {
        ToolProfile profile = ToolProfile.builder("hidden-argument")
                .server(FrameworkFixtures.SERVER_ALPHA)
                .allowTool(FrameworkFixtures.SERVER_ALPHA, "search")
                .hideArgument(FrameworkFixtures.SERVER_ALPHA, "search", "pql")
                .build();
        ToolSpecification search = ToolSpecification.builder()
                .name("search")
                .description("Search")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("query")
                        .addStringProperty("pql")
                        .required("query", "pql")
                        .build())
                .build();
        McpToolPolicy client = policy(
                new FakeMcpClient(List.of(search)),
                FrameworkFixtures.SERVER_ALPHA,
                profile);

        ToolSpecification exposed = tools(client).getFirst();

        assertTrue(exposed.parameters().properties().containsKey("query"));
        assertFalse(exposed.parameters().properties().containsKey("pql"));
        assertEquals(List.of("query"), exposed.parameters().required());
    }

    @Test
    void hiddenArgumentIsRejectedAtExecution() {
        ToolProfile profile = ToolProfile.builder("hidden-argument")
                .server(FrameworkFixtures.SERVER_ALPHA)
                .allowTool(FrameworkFixtures.SERVER_ALPHA, "search")
                .hideArgument(FrameworkFixtures.SERVER_ALPHA, "search", "pql")
                .build();
        McpToolPolicy client = policy(
                new FakeMcpClient(List.of(ToolSpecification.builder().name("search").description("Search").build())),
                FrameworkFixtures.SERVER_ALPHA,
                profile);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("1")
                .name("search")
                .arguments("{\"query\":\"open\",\"pql\":\"priority = high\"}")
                .build();

        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> client.prepareRequest(request));

        assertEquals(
                "Argument pql is hidden by profile hidden-argument for tool search",
                failure.getMessage());
    }

    @Test
    void compositeProfilesExposeServerQualifiedToolNamesAndExecuteOriginalNames() throws Exception {
        ToolProfile profile = ToolProfile.builder("composite")
                .mcpServer(McpServerDefinition.builder(FrameworkFixtures.SERVER_ALPHA).url("http://alpha.example/mcp").build())
                .mcpServer(McpServerDefinition.builder(FrameworkFixtures.SERVER_BETA).url("http://beta.example/mcp").build())
                .allowTool(FrameworkFixtures.SERVER_ALPHA, "get_me")
                .allowTool(FrameworkFixtures.SERVER_BETA, "get_me")
                .defaultArgument(FrameworkFixtures.SERVER_ALPHA, "get_me", "fields", "id,name")
                .build();
        FakeMcpClient alphaDelegate = new FakeMcpClient(List.of(ToolSpecification.builder().name("get_me").description("Current user").build()));
        FakeMcpClient betaDelegate = new FakeMcpClient(List.of(ToolSpecification.builder().name("get_me").description("Current user").build()));
        McpToolPolicy alpha = policy(alphaDelegate, FrameworkFixtures.SERVER_ALPHA, profile);
        McpToolPolicy beta = policy(betaDelegate, FrameworkFixtures.SERVER_BETA, profile);

        assertEquals(List.of("alpha_mcp__get_me"), tools(alpha).stream().map(ToolSpecification::name).toList());
        assertEquals(List.of("beta_mcp__get_me"), tools(beta).stream().map(ToolSpecification::name).toList());

        ToolExecutionRequest exposedRequest = ToolExecutionRequest.builder()
                .id("1")
                .name("alpha_mcp__get_me")
                .arguments("{}")
                .build();
        ToolExecutionRequest delegateRequest = alpha.prepareRequest(exposedRequest);

        assertEquals("get_me", delegateRequest.name());
        assertEquals("id,name", OBJECT_MAPPER.readTree(delegateRequest.arguments()).get("fields").asText());

        execute(alpha, exposedRequest);
        assertEquals("get_me", alphaDelegate.lastToolName());
    }

    @Test
    void singleServerProfileKeepsOriginalToolNames() {
        ToolProfile profile = ToolProfile.builder("single-server")
                .mcpServer(McpServerDefinition.builder(FrameworkFixtures.SERVER_ALPHA).url("http://alpha.example/mcp").build())
                .allowTool(FrameworkFixtures.SERVER_ALPHA, "get_me")
                .build();
        McpToolPolicy client = policy(
                new FakeMcpClient(List.of(ToolSpecification.builder().name("get_me").description("Current user").build())),
                FrameworkFixtures.SERVER_ALPHA,
                profile);

        assertEquals(List.of("get_me"), tools(client).stream().map(ToolSpecification::name).toList());
    }

    @Test
    void jsonDefaultArgumentsSupportScalarsAndArrays() throws Exception {
        ToolProfile profile = ToolProfile.builder("json-defaults")
                .server(FrameworkFixtures.SERVER_ALPHA)
                .defaultArgument(FrameworkFixtures.SERVER_ALPHA, "alpha_search", "includeClosed", true)
                .defaultArgument(FrameworkFixtures.SERVER_ALPHA, "alpha_search", "limit", 5)
                .defaultArgument(FrameworkFixtures.SERVER_ALPHA, "alpha_search", "query", "parking")
                .defaultArgumentJson(FrameworkFixtures.SERVER_ALPHA, "alpha_search", "states", "[\"open\",\"started\"]")
                .build();
        McpToolPolicy client = policy(null, FrameworkFixtures.SERVER_ALPHA, profile);

        ToolExecutionRequest request = ToolExecutionRequest.builder().id("1").name("alpha_search").arguments("{}").build();

        JsonNode arguments = OBJECT_MAPPER.readTree(client.prepareRequest(request).arguments());
        assertTrue(arguments.get("includeClosed").asBoolean());
        assertEquals(5, arguments.get("limit").asInt());
        assertEquals("parking", arguments.get("query").asText());
        assertEquals("open", arguments.get("states").get(0).asText());
    }

    @Test
    void serverDeclaresLogicalToolSurfaceButMcpServerDefinesBuildableClient() {
        ToolProfile logicalOnly = ToolProfile.builder("logical-only")
                .server(FrameworkFixtures.SERVER_GAMMA)
                .build();
        ToolProfile buildable = ToolProfile.builder("buildable")
                .mcpServer(McpServerDefinition.builder(FrameworkFixtures.SERVER_GAMMA).url("http://gamma.example/mcp").build())
                .build();

        assertTrue(logicalOnly.allowsServer(FrameworkFixtures.SERVER_GAMMA));
        assertTrue(logicalOnly.mcpServers().isEmpty());
        assertTrue(buildable.allowsServer(FrameworkFixtures.SERVER_GAMMA));
        assertEquals("http://gamma.example/mcp", buildable.mcpServers().get(FrameworkFixtures.SERVER_GAMMA).url());
    }

    @Test
    void profileDefaultsDoNotOverrideCallerProvidedArguments() throws Exception {
        ToolProfile profile = FrameworkFixtures.toolProfileRegistry().getRequired(FrameworkFixtures.PROFILE_BETA);
        McpToolPolicy client = policy(null, FrameworkFixtures.SERVER_BETA, profile);

        ToolExecutionRequest request = ToolExecutionRequest.builder().id("1").name("beta_list").arguments("{\"fields\":\"id,name\",\"limit\":5}").build();

        JsonNode arguments = OBJECT_MAPPER.readTree(client.prepareRequest(request).arguments());

        assertEquals("id,name", arguments.get("fields").asText());
        assertEquals(5, arguments.get("limit").asInt());
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
        McpToolPolicy client = policy(new FakeMcpClient(), FrameworkFixtures.SERVER_GAMMA, profile);

        assertEquals(List.of("gamma_get_pull_request"), tools(client).stream().map(ToolSpecification::name).toList());
    }

    @Test
    void broadProfileWithoutAllowListExposesToolsExceptDeniedOnes() {
        ToolProfile profile = ToolProfile.builder("broad")
                .server(FrameworkFixtures.SERVER_GAMMA)
                .denyTool(FrameworkFixtures.SERVER_GAMMA, "delete_repository")
                .build();
        McpToolPolicy client = policy(new FakeMcpClient(), FrameworkFixtures.SERVER_GAMMA, profile);

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
        McpToolPolicy client = policy(new FakeMcpClient(), FrameworkFixtures.SERVER_ALPHA, profile);

        assertTrue(tools(client).isEmpty());
        assertFalse(profile.allowsTool(FrameworkFixtures.SERVER_ALPHA, "alpha_read"));
    }

    @Test
    void profileRejectsToolsOutsideTheDeclaredToolSurface() {
        ToolProfile profile = ToolProfile.builder("safe-default")
                .server(FrameworkFixtures.SERVER_GAMMA)
                .denyTool(FrameworkFixtures.SERVER_GAMMA, "delete_repository")
                .build();
        McpToolPolicy client = policy(new FakeMcpClient(), FrameworkFixtures.SERVER_GAMMA, profile);
        ToolExecutionRequest request = ToolExecutionRequest.builder().id("1").name("delete_repository").arguments("{}").build();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> client.prepareRequest(request));

        assertEquals("Tool delete_repository is not allowed by profile safe-default", failure.getMessage());
    }

    @Test
    void defaultArgumentsRemainScopedByServerAndTool() {
        ToolProfile profile = ToolProfile.builder("scoped-defaults")
                .server(FrameworkFixtures.SERVER_ALPHA)
                .server(FrameworkFixtures.SERVER_BETA)
                .defaultArgument(FrameworkFixtures.SERVER_ALPHA, "search", "query", "alpha")
                .defaultArgument(FrameworkFixtures.SERVER_BETA, "search", "query", "beta")
                .defaultArgument(FrameworkFixtures.SERVER_BETA, "list", "limit", 5)
                .build();

        assertEquals("alpha", profile.defaultArguments(FrameworkFixtures.SERVER_ALPHA, "search").get("query").asText());
        assertEquals("beta", profile.defaultArguments(FrameworkFixtures.SERVER_BETA, "search").get("query").asText());
        assertTrue(profile.defaultArguments(FrameworkFixtures.SERVER_ALPHA, "list").isEmpty());
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

        assertEquals("http://alpha.example/mcp", first.mcpServers().get(FrameworkFixtures.SERVER_ALPHA).url());
        assertEquals("one", second.mcpServers().get(FrameworkFixtures.SERVER_ALPHA).extraHeaders().get("x-synthetic"));
        assertTrue(first.allowsTool(FrameworkFixtures.SERVER_ALPHA, "alpha_read"));
        assertFalse(second.allowsTool(FrameworkFixtures.SERVER_ALPHA, "alpha_delete"));
        assertThrows(UnsupportedOperationException.class, () -> shared.extraHeaders().put("x-other", "two"));
    }

    @Test
    void allJsonIncludesAllowedAndDeniedToolPolicy() {
        ToolProfileRegistry registry = new ToolProfileRegistry(List.of(ToolProfile.builder("json-policy")
                .server(FrameworkFixtures.SERVER_ALPHA)
                .allowTool(FrameworkFixtures.SERVER_ALPHA, "alpha_read")
                .denyTool(FrameworkFixtures.SERVER_ALPHA, "alpha_delete")
                .build()));

        String json = registry.allJson();

        assertTrue(json.contains("allowedTools"));
        assertTrue(json.contains("alpha_read"));
        assertTrue(json.contains("deniedTools"));
        assertTrue(json.contains("alpha_delete"));
        assertTrue(json.contains("hiddenArguments"));
    }

    @Test
    void toolResultsAreLimitedByActiveProfile() {
        ToolProfile profile = ToolProfile.builder("tiny")
                .server(FrameworkFixtures.SERVER_GAMMA)
                .allowTool(FrameworkFixtures.SERVER_GAMMA, "gamma_get_pull_request")
                .maxResultTokens(10)
                .build();
        McpToolPolicy client = policy(new FakeMcpClient("x".repeat(100)), FrameworkFixtures.SERVER_GAMMA, profile, null, null, 0, TOKEN_ESTIMATOR);

        ToolExecutionResult result = execute(client, ToolExecutionRequest.builder().id("1").name("gamma_get_pull_request").arguments("{}").build());

        assertTrue(TOKEN_ESTIMATOR.estimateTokenCountInText(result.resultText()) <= 10);
        assertTrue(result.resultText().contains("...(truncated, original 25 tokens)"));
    }

    @Test
    void globalLimitAppliesWhenProfileDoesNotOverrideIt() {
        ToolProfile profile = ToolProfile.builder("global-limited")
                .server(FrameworkFixtures.SERVER_GAMMA)
                .allowTool(FrameworkFixtures.SERVER_GAMMA, "gamma_get_pull_request")
                .build();
        McpToolPolicy client = policy(new FakeMcpClient("x".repeat(100)), FrameworkFixtures.SERVER_GAMMA, profile, null, null, 10, TOKEN_ESTIMATOR);

        ToolExecutionResult result = execute(client, ToolExecutionRequest.builder().id("1").name("gamma_get_pull_request").arguments("{}").build());

        assertTrue(TOKEN_ESTIMATOR.estimateTokenCountInText(result.resultText()) <= 10);
        assertTrue(result.resultText().contains("...(truncated, original 25 tokens)"));
    }

    @Test
    void profileSpecificLimitOverridesGlobalDefault() {
        String longResult = "x".repeat(1500);
        ToolProfile profile = ToolProfile.builder("large")
                .server(FrameworkFixtures.SERVER_GAMMA)
                .allowTool(FrameworkFixtures.SERVER_GAMMA, "gamma_get_pull_request")
                .maxResultTokens(5000)
                .build();
        McpToolPolicy client = policy(new FakeMcpClient(longResult), FrameworkFixtures.SERVER_GAMMA, profile, null, null, 100, TOKEN_ESTIMATOR);

        ToolExecutionResult result = execute(client, ToolExecutionRequest.builder().id("1").name("gamma_get_pull_request").arguments("{}").build());

        assertEquals(longResult, result.resultText());
    }

    @Test
    void disabledGlobalLimitAllowsUnlimitedResultsWhenProfileDoesNotSetLimit() {
        String longResult = "x".repeat(1500);
        ToolProfile profile = ToolProfile.builder("unlimited")
                .server(FrameworkFixtures.SERVER_GAMMA)
                .allowTool(FrameworkFixtures.SERVER_GAMMA, "gamma_get_pull_request")
                .build();
        McpToolPolicy client = policy(new FakeMcpClient(longResult), FrameworkFixtures.SERVER_GAMMA, profile, null, null, 0);

        ToolExecutionResult result = execute(client, ToolExecutionRequest.builder().id("1").name("gamma_get_pull_request").arguments("{}").build());

        assertEquals(longResult, result.resultText());
    }

    @Test
    void dimmedToolResponseLogUsesLimitedResultText() {
        String hugeResult = "x".repeat(100);
        ToolProfile profile = ToolProfile.builder("logged")
                .server(FrameworkFixtures.SERVER_GAMMA)
                .allowTool(FrameworkFixtures.SERVER_GAMMA, "gamma_get_pull_request")
                .build();
        CapturingLogTokens logs = new CapturingLogTokens();
        Persona persona = FrameworkFixtures.personaCatalog().getRequired(FrameworkFixtures.PERSONA_A);
        McpToolPolicy client = policy(new FakeMcpClient(hugeResult), FrameworkFixtures.SERVER_GAMMA, profile, persona, logs, 10, TOKEN_ESTIMATOR);

        ToolExecutionResult result = execute(client, ToolExecutionRequest.builder().id("1").name("gamma_get_pull_request").arguments("{}").build());

        assertTrue(TOKEN_ESTIMATOR.estimateTokenCountInText(result.resultText()) <= 10);
        assertTrue(result.resultText().contains("...(truncated, original 25 tokens)"));
        assertTrue(logs.messages.stream().anyMatch(message -> message.contains("RESPONSE")));
        assertFalse(String.join("\n", logs.messages).contains(hugeResult));
        assertTrue(String.join("\n", logs.messages).contains("...(truncated"));
    }

    @Test
    void genericToolProfilesDeclareToolSurfaceWithoutScenarioSpecificProfiles() {
        ToolProfile profile = FrameworkFixtures.toolProfileRegistry().getRequired(FrameworkFixtures.PROFILE_BETA);

        assertTrue(profile.allowsServer(FrameworkFixtures.SERVER_BETA));
        assertTrue(profile.allowsTool(FrameworkFixtures.SERVER_BETA, "beta_list"));
        assertTrue(profile.allowsTool(FrameworkFixtures.SERVER_BETA, "beta_create"));
        assertFalse(profile.allowsTool(FrameworkFixtures.SERVER_BETA, "beta_delete"));
        assertEquals("id,name,identifier", profile.defaultArguments(FrameworkFixtures.SERVER_BETA, "beta_list").get("fields").asText());
    }

    @Test
    void noneProfileIsFrameworkBuiltInAndExcludedFromCatalogListings() {
        ToolProfileRegistry registry = new ToolProfileRegistry(List.of(ToolProfile.builder("tools").server(FrameworkFixtures.SERVER_GAMMA).build()));

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
        private final List<ToolSpecification> tools;
        private ToolExecutionRequest lastRequest;

        FakeMcpClient() {
            this("abcdefghijklmnopqrstuvwxyz0123456789");
        }

        FakeMcpClient(String resultText) {
            this(resultText, defaultTools());
        }

        FakeMcpClient(List<ToolSpecification> tools) {
            this("abcdefghijklmnopqrstuvwxyz0123456789", tools);
        }

        FakeMcpClient(String resultText, List<ToolSpecification> tools) {
            this.resultText = resultText;
            this.tools = tools;
        }

        String lastToolName() {
            return lastRequest == null ? null : lastRequest.name();
        }

        @Override public String key() { return "fake"; }
        @Override public List<ToolSpecification> listTools() { return tools; }
        @Override public List<ToolSpecification> listTools(InvocationContext invocationContext) { return listTools(); }
        @Override public ToolExecutionResult executeTool(ToolExecutionRequest executionRequest) { this.lastRequest = executionRequest; return ToolExecutionResult.builder().isError(false).resultText(resultText).build(); }
        @Override public ToolExecutionResult executeTool(ToolExecutionRequest executionRequest, InvocationContext invocationContext) { return executeTool(executionRequest); }

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
