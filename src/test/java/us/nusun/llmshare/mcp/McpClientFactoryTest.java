package us.nusun.llmshare.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpGetPromptResult;
import dev.langchain4j.mcp.client.McpPrompt;
import dev.langchain4j.mcp.client.McpReadResourceResult;
import dev.langchain4j.mcp.client.McpResource;
import dev.langchain4j.mcp.client.McpResourceTemplate;
import dev.langchain4j.mcp.client.McpRoot;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.bean.LogTokens;
import us.nusun.llmshare.bean.RoutineToolTraceTracker;
import us.nusun.llmshare.config.AgentIdentityConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpClientFactoryTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void tokenLookupUsesDifferentRoleSpecificProperties() {
        McpClientFactory factory = factory();

        assertEquals("Bearer mm-director", factory.mattermostConnection("director").headers().get("Authorization"));
        assertEquals("Bearer mm-pm", factory.mattermostConnection("project-manager").headers().get("Authorization"));
        assertEquals("token gitea-po", factory.giteaConnection("product-owner").headers().get("Authorization"));
        assertEquals("token gitea-dev", factory.giteaConnection("application-developer").headers().get("Authorization"));
    }

    @Test
    void planeConnectionIncludesApiKeyAndWorkspaceSlugHeaders() {
        McpClientFactory factory = factory();
        McpClientFactory.McpServiceConnection connection = factory.planeConnection("director");

        assertEquals("plane-director", connection.headers().get("x-api-key"));
        assertEquals("inner-dev", connection.headers().get("x-workspace-slug"));
    }

    @Test
    void duplicateGetMeIsFilteredFromGiteaAndPlaneButNotMattermost() {
        McpClientFactory factory = factory();
        BiPredicate<McpClient, ToolSpecification> filter = factory.toolFilterFor("development");
        ToolSpecification getMe = ToolSpecification.builder().name("get_me").description("identity").build();

        assertTrue(filter.test(new FakeMcpClient("mattermost:director"), getMe));
        assertFalse(filter.test(new FakeMcpClient("gitea:director"), getMe));
        assertFalse(filter.test(new FakeMcpClient("plane:director"), getMe));
    }

    @Test
    void filteredVisibleToolNamesRemainUnique() {
        McpClientFactory factory = factory();
        BiPredicate<McpClient, ToolSpecification> filter = factory.toolFilterFor("planning");
        Map<McpClient, List<ToolSpecification>> toolsByClient = new LinkedHashMap<>();
        toolsByClient.put(new FakeMcpClient("mattermost:project-manager"), List.of(
                ToolSpecification.builder().name("get_me").build(),
                ToolSpecification.builder().name("search_posts").build()));
        toolsByClient.put(new FakeMcpClient("gitea:project-manager"), List.of(
                ToolSpecification.builder().name("get_me").build(),
                ToolSpecification.builder().name("list_repos").build()));
        toolsByClient.put(new FakeMcpClient("plane:project-manager"), List.of(
                ToolSpecification.builder().name("get_me").build(),
                ToolSpecification.builder().name("list_projects").build()));

        List<String> visibleNames = toolsByClient.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().filter(tool -> filter.test(entry.getKey(), tool)))
                .map(ToolSpecification::name)
                .toList();

        assertTrue(visibleNames.contains("get_me"));
        assertTrue(visibleNames.contains("list_repos"));
        assertTrue(visibleNames.contains("list_projects"));
        assertTrue(visibleNames.contains("search_posts"));
        assertEquals(visibleNames.size(), Set.copyOf(visibleNames).size());
        assertEquals(1L, visibleNames.stream().filter("get_me"::equals).count());
    }

    @Test
    void boundedToolResultExtractorReturnsErrorWhenTextExceedsLimit() throws Exception {
        BoundedMcpToolResultExtractor extractor = new BoundedMcpToolResultExtractor(10);

        ToolExecutionResult result = extractor.extract(
                OBJECT_MAPPER.readTree("""
                        [{"type":"text","text":"01234567890"}]
                        """),
                false);

        assertTrue(result.isError());
        assertEquals(BoundedMcpToolResultExtractor.SUPPRESSED_MESSAGE, result.resultText());
    }

    @Test
    void boundedToolResultExtractorPreservesSmallTextResults() throws Exception {
        BoundedMcpToolResultExtractor extractor = new BoundedMcpToolResultExtractor(10);

        ToolExecutionResult result = extractor.extract(
                OBJECT_MAPPER.readTree("""
                        [{"type":"text","text":"ok"}]
                        """),
                false);

        assertFalse(result.isError());
        assertEquals("ok", result.resultText());
    }

    @Test
    void factoryConfigExposesDefaultMaxToolResultCharacters() {
        McpClientFactory factory = factory();

        assertNotNull(factory);
        assertEquals(1000, new FakeAgentIdentityConfig().mcp().maxToolResultCharacters());
    }

    private static McpClientFactory factory() {
        return new McpClientFactory(new FakeAgentIdentityConfig(), new LogTokens(), new RoutineToolTraceTracker());
    }

    private static final class FakeAgentIdentityConfig implements AgentIdentityConfig {
        @Override
        public Mcp mcp() {
            return new Mcp() {
                @Override
                public int maxToolResultCharacters() {
                    return 1000;
                }

                @Override
                public long transportTimeoutMs() {
                    return 60000;
                }

                @Override
                public long initializationTimeoutMs() {
                    return 30000;
                }

                @Override
                public Logging logging() {
                    return new Logging() {
                        @Override
                        public boolean enabled() {
                            return true;
                        }

                        @Override
                        public boolean transportRequests() {
                            return false;
                        }

                        @Override
                        public boolean transportResponses() {
                            return false;
                        }
                    };
                }

                @Override
                public StartupCheck startupCheck() {
                    return new StartupCheck() {
                        @Override
                        public boolean enabled() {
                            return false;
                        }

                        @Override
                        public boolean failFast() {
                            return false;
                        }
                    };
                }

                @Override
                public Service mattermost() {
                    return service("https://mattermost.invalid/mcp", "streamableHttp", "Authorization", "Bearer %s", List.of());
                }

                @Override
                public Service gitea() {
                    return service("https://gitea.invalid/mcp", "streamableHttp", "Authorization", "token %s", List.of("get_me"));
                }

                @Override
                public PlaneService plane() {
                    return new PlaneService() {
                        @Override
                        public Optional<String> workspaceSlug() {
                            return Optional.of("inner-dev");
                        }

                        @Override
                        public Optional<String> url() {
                            return Optional.of("https://plane.invalid/mcp");
                        }

                        @Override
                        public String transportType() {
                            return "streamableHttp";
                        }

                        @Override
                        public Optional<String> authHeaderName() {
                            return Optional.of("x-api-key");
                        }

                        @Override
                        public Optional<String> authHeaderValueFormat() {
                            return Optional.of("%s");
                        }

                        @Override
                        public Optional<List<String>> disabledTools() {
                            return Optional.of(List.of("get_me"));
                        }
                    };
                }

                private Service service(
                        String url,
                        String transportType,
                        String headerName,
                        String valueFormat,
                        List<String> disabledTools) {
                    return new Service() {
                        @Override
                        public Optional<String> url() {
                            return Optional.of(url);
                        }

                        @Override
                        public String transportType() {
                            return transportType;
                        }

                        @Override
                        public Optional<String> authHeaderName() {
                            return Optional.of(headerName);
                        }

                        @Override
                        public Optional<String> authHeaderValueFormat() {
                            return Optional.of(valueFormat);
                        }

                        @Override
                        public Optional<List<String>> disabledTools() {
                            return Optional.of(disabledTools);
                        }
                    };
                }
            };
        }

        @Override
        public Identity identity() {
            return new Identity() {
                @Override
                public Role director() {
                    return role("mm-director", "gitea-director", "plane-director");
                }

                @Override
                public Role projectManager() {
                    return role("mm-pm", "gitea-pm", "plane-pm");
                }

                @Override
                public Role productOwner() {
                    return role("mm-po", "gitea-po", "plane-po");
                }

                @Override
                public Role applicationDeveloper() {
                    return role("mm-dev", "gitea-dev", "plane-dev");
                }

                private Role role(String mattermostToken, String giteaToken, String planeToken) {
                    return new Role() {
                        @Override
                        public TokenService mattermost() {
                            return () -> Optional.of(mattermostToken);
                        }

                        @Override
                        public TokenService gitea() {
                            return () -> Optional.of(giteaToken);
                        }

                        @Override
                        public TokenService plane() {
                            return () -> Optional.of(planeToken);
                        }
                    };
                }
            };
        }
    }

    private static final class FakeMcpClient implements McpClient {
        private final String key;

        private FakeMcpClient(String key) {
            this.key = key;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public List<ToolSpecification> listTools() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ToolSpecification> listTools(InvocationContext invocationContext) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ToolExecutionResult executeTool(ToolExecutionRequest executionRequest) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ToolExecutionResult executeTool(ToolExecutionRequest executionRequest, InvocationContext invocationContext) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<McpResource> listResources() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<McpResource> listResources(InvocationContext invocationContext) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<McpResourceTemplate> listResourceTemplates() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<McpResourceTemplate> listResourceTemplates(InvocationContext invocationContext) {
            throw new UnsupportedOperationException();
        }

        @Override
        public McpReadResourceResult readResource(String uri) {
            throw new UnsupportedOperationException();
        }

        @Override
        public McpReadResourceResult readResource(String uri, InvocationContext invocationContext) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void subscribeToResource(String uri) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void unsubscribeFromResource(String uri) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<McpPrompt> listPrompts() {
            throw new UnsupportedOperationException();
        }

        @Override
        public McpGetPromptResult getPrompt(String name, Map<String, Object> arguments) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void checkHealth() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setRoots(List<McpRoot> roots) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }
}
