package us.nusun.llmshare.framework.tests.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import us.nusun.llmshare.framework.inventory.ExecutionRuntimeRegistry;
import us.nusun.llmshare.framework.inventory.PersonaCatalog;
import us.nusun.llmshare.framework.inventory.RoutineCatalog;
import us.nusun.llmshare.framework.inventory.ToolProfileRegistry;
import us.nusun.llmshare.framework.model.ExecutionRuntimeDefinition;
import us.nusun.llmshare.framework.model.InferenceLaneDefinition;
import us.nusun.llmshare.framework.model.LlmJobResult;
import us.nusun.llmshare.framework.model.LlmJobStatus;
import us.nusun.llmshare.framework.model.Mission;
import us.nusun.llmshare.framework.model.Persona;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.model.ToolProfile;
import us.nusun.llmshare.framework.projection.AgentRunProjectionManager;
import us.nusun.llmshare.framework.projection.ProjectionStore;
import us.nusun.llmshare.framework.routine.PrimitiveSupport;
import us.nusun.llmshare.framework.routine.ThoughtStackSupport;
import us.nusun.llmshare.framework.state.AgentRun;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class FrameworkFixtures {
    public static final String PERSONA_A = "persona-a";
    public static final String PERSONA_B = "persona-b";
    public static final String ROUTINE_A = "routine-a";
    public static final String ROUTINE_B = "routine-b";

    private FrameworkFixtures() {
    }

    public static List<Persona> personas() {
        return List.of(
                Persona.builder(PERSONA_A)
                        .name("Persona A")
                        .description("Synthetic framework persona A")
                        .label("PA")
                        .color("38;2;106;169;255")
                        .trait("Builds framework test scenarios")
                        .identity("mattermost", "mattermost-token", "@persona-a")
                        .identity("gitea", "gitea-token", "@persona-a")
                        .identity("plane", "plane-token", "@persona-a")
                        .build(),
                Persona.builder(PERSONA_B)
                        .name("Persona B")
                        .description("Synthetic framework persona B")
                        .label("PB")
                        .color("38;2;255;179;71")
                        .trait("Reviews framework test scenarios")
                        .agentRunStartAssignable(false)
                        .build());
    }

    public static PersonaCatalog personaCatalog() {
        return new PersonaCatalog(personas());
    }

    public static List<RoutineDefinition> routines() {
        return List.of(
                RoutineDefinition.builder(ROUTINE_A)
                        .name("Routine A")
                        .description("Synthetic framework routine A")
                        .routeUri("direct:routine-a")
                        .defaultToolRuntime("tool-low")
                        .defaultSynthesisRuntime("synthesis-low")
                        .build(),
                RoutineDefinition.builder(ROUTINE_B)
                        .name("Routine B")
                        .description("Synthetic framework routine B")
                        .routeUri("direct:routine-b")
                        .defaultToolRuntime("tool-high")
                        .defaultSynthesisRuntime("synthesis-high")
                        .build());
    }

    public static RoutineCatalog routineCatalog() {
        return new RoutineCatalog(routines());
    }

    public static List<ToolProfile> toolProfiles() {
        return List.of(
                ToolProfile.builder("team-chat")
                        .allowTools("mattermost", "create_post", "get_channel")
                        .maxResultCharacters(1500)
                        .build(),
                ToolProfile.builder("project-management")
                        .allowTools("plane", "list_projects", "create_issue")
                        .defaultArgument("plane", "list_projects", "fields", "id,name,identifier")
                        .maxResultCharacters(1500)
                        .build(),
                ToolProfile.builder("source-control")
                        .allowTools("gitea", "get_repository", "get_pull_request")
                        .maxResultCharacters(1500)
                        .build());
    }

    public static ToolProfileRegistry toolProfileRegistry() {
        return new ToolProfileRegistry(toolProfiles());
    }

    public static List<InferenceLaneDefinition> inferenceLanes() {
        return List.of(
                InferenceLaneDefinition.builder("low-pass")
                        .requestTopic("llm.requests.low")
                        .responseTopic("llm.replies.low")
                        .parallelism(1)
                        .endpoint("http://low-vllm.example/v1")
                        .apiKeyConfigKey("llm.inference-lane.low.api-key")
                        .apiKeyEnvVariable("LLM_LOW_API_KEY")
                        .build(),
                InferenceLaneDefinition.builder("high-pass")
                        .requestTopic("llm.requests.high")
                        .responseTopic("llm.replies.high")
                        .parallelism(1)
                        .endpoint("http://high-vllm.example/v1")
                        .apiKeyConfigKey("llm.inference-lane.high.api-key")
                        .apiKeyEnvVariable("LLM_HIGH_API_KEY")
                        .build());
    }

    public static List<ExecutionRuntimeDefinition> executionRuntimes() {
        return List.of(
                ExecutionRuntimeDefinition.builder("tool-low")
                        .lane("low-pass")
                        .toolCalling(true)
                        .agentFactoryBeanName("executionRuntimeAgentFactory")
                        .build(),
                ExecutionRuntimeDefinition.builder("tool-high")
                        .lane("high-pass")
                        .toolCalling(true)
                        .agentFactoryBeanName("executionRuntimeAgentFactory")
                        .build(),
                ExecutionRuntimeDefinition.builder("synthesis-low")
                        .lane("low-pass")
                        .toolCalling(false)
                        .agentFactoryBeanName("executionRuntimeAgentFactory")
                        .build(),
                ExecutionRuntimeDefinition.builder("synthesis-high")
                        .lane("high-pass")
                        .toolCalling(false)
                        .agentFactoryBeanName("executionRuntimeAgentFactory")
                        .build());
    }

    public static ExecutionRuntimeRegistry executionRuntimeRegistry() {
        return new ExecutionRuntimeRegistry(inferenceLanes(), executionRuntimes());
    }

    public static PrimitiveSupport primitiveSupport() {
        PrimitiveSupport support = new PrimitiveSupport();
        set(support, "objectMapper", new ObjectMapper());
        set(support, "thoughts", new ThoughtStackSupport());
        set(support, "personas", personaCatalog());
        set(support, "routines", routineCatalog());
        set(support, "runtimes", executionRuntimeRegistry());
        set(support, "projections", new NoopProjectionManager());
        return support;
    }

    public static void bindCatalogBeans(DefaultCamelContext context) {
        context.getRegistry().bind("personaCatalog", personaCatalog());
        context.getRegistry().bind("routineCatalog", routineCatalog());
        context.getRegistry().bind("toolProfileCatalog", toolProfileRegistry());
    }

    public static ProjectionStore projectionStore() {
        return new ProjectionStore();
    }

    public static AgentRunProjectionManager projectionManager(ProjectionStore store) {
        AgentRunProjectionManager manager = new AgentRunProjectionManager();
        set(manager, "store", store);
        set(manager, "personas", personaCatalog());
        set(manager, "routines", routineCatalog());
        set(manager, "runtimes", executionRuntimeRegistry());
        return manager;
    }

    public static Exchange exchangeWithRun(String personaId, String routineId) {
        DefaultExchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.setProperty("mission", new Mission("mission-1", "Test mission", List.of(), "active", Map.of(), Instant.now()));
        exchange.setProperty("agentRun", new AgentRun("run-1", "mission-1", personaId, routineId, Map.of()));
        return exchange;
    }

    public static LlmJobResult llmResult(String runtimeId, String content) {
        Instant now = Instant.now();
        return new LlmJobResult(UUID.randomUUID(), LlmJobStatus.SUCCEEDED, runtimeId, "test", "mock", content, content, now, now, now, 0, null);
    }

    public static void set(Object target, String name, Object value) {
        try {
            Field field = field(target.getClass(), name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to set test dependency " + name, e);
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static class NoopProjectionManager extends AgentRunProjectionManager {
        @Override
        public void record(Exchange exchange, String eventType) {
        }
    }
}
