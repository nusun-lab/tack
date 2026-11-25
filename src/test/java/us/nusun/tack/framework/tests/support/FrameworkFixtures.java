package us.nusun.tack.framework.tests.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.TokenCountEstimator;
import us.nusun.tack.framework.inference.lane.ToolPassSettings;
import us.nusun.tack.framework.inference.LlmAgentFactory;
import us.nusun.tack.framework.inference.LlmRouteSupport;
import us.nusun.tack.framework.tool.mcp.McpClientFactory;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import us.nusun.tack.framework.inference.LlmJobFactory;
import us.nusun.tack.framework.inference.LlmJobEnvelope;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import us.nusun.tack.framework.inference.lane.InferenceLaneRegistry;
import us.nusun.tack.framework.persona.PersonaCatalog;
import us.nusun.tack.framework.routine.RoutineCatalog;
import us.nusun.tack.framework.tool.ToolProfileRegistry;
import us.nusun.tack.framework.inference.lane.InferenceLaneDefinition;
import us.nusun.tack.framework.inference.LlmJobResult;
import us.nusun.tack.framework.inference.LlmJobStatus;
import us.nusun.tack.framework.inference.LlmPassType;
import us.nusun.tack.framework.run.Mission;
import us.nusun.tack.framework.persona.Persona;
import us.nusun.tack.framework.routine.RoutineDefinition;
import us.nusun.tack.framework.tool.ToolProfile;
import us.nusun.tack.framework.run.AgentRunProjectionManager;
import us.nusun.tack.framework.run.ProjectionStore;
import us.nusun.tack.framework.routine.PrimitiveExecutor;
import us.nusun.tack.framework.run.MissionRosterService;
import us.nusun.tack.framework.run.AgentRun;
import us.nusun.tack.framework.run.AgentRunStore;

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
    public static final String SERVER_ALPHA = "alpha-mcp";
    public static final String SERVER_BETA = "beta-mcp";
    public static final String SERVER_GAMMA = "gamma-mcp";
    public static final String PROFILE_ALPHA = "profile-alpha";
    public static final String PROFILE_BETA = "profile-beta";
    public static final String PROFILE_GAMMA = "profile-gamma";

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
                        .identity(SERVER_ALPHA, "alpha-secret", "@persona-a")
                        .identity(SERVER_BETA, "beta-secret", "@persona-a")
                        .identity(SERVER_GAMMA, "gamma-secret", "@persona-a")
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
                        .defaultLane("low-pass")
                        .build(),
                RoutineDefinition.builder(ROUTINE_B)
                        .name("Routine B")
                        .description("Synthetic framework routine B")
                        .routeUri("direct:routine-b")
                        .defaultLane("high-pass")
                        .build());
    }

    public static RoutineCatalog routineCatalog() {
        return new RoutineCatalog(routines());
    }

    public static List<ToolProfile> toolProfiles() {
        return List.of(
                ToolProfile.builder(PROFILE_ALPHA)
                        .allowTools(SERVER_ALPHA, "alpha_post", "alpha_get")
                        .promptGuidance("Read recent synthetic context before posting.")
                        .maxResultTokens(1500)
                        .build(),
                ToolProfile.builder(PROFILE_BETA)
                        .allowTools(SERVER_BETA, "beta_list", "beta_create")
                        .defaultArgument(SERVER_BETA, "beta_list", "fields", "id,name,identifier")
                        .promptGuidance("Find existing synthetic work before creating new items.")
                        .maxResultTokens(1500)
                        .build(),
                ToolProfile.builder(PROFILE_GAMMA)
                        .allowTools(SERVER_GAMMA, "gamma_get_repository", "gamma_get_pull_request")
                        .promptGuidance("Read before writing and avoid duplicate branches or pull requests.")
                        .maxResultTokens(1500)
                        .build());
    }

    public static ToolProfileRegistry toolProfileRegistry() {
        return new ToolProfileRegistry(toolProfiles());
    }

    public static List<InferenceLaneDefinition> inferenceLanes() {
        return List.of(
                InferenceLaneDefinition.builder("low-pass")
                        .requestTopic("test.llm.requests.low")
                        .responseTopic("test.llm.replies.low")
                        .parallelism(1)
                        .endpoint("http://low-vllm.example/v1")
                        .build(),
                InferenceLaneDefinition.builder("high-pass")
                        .requestTopic("test.llm.requests.high")
                        .responseTopic("test.llm.replies.high")
                        .parallelism(1)
                        .endpoint("http://high-vllm.example/v1")
                        .build());
    }

    public static LlmAgentFactory.DynamicAgentFactory agentFactory(
            String lane, ChatModel model, McpClientFactory clients, TokenCountEstimator estimator,
            int memoryTokens, int maxToolCalls, LlmRouteSupport routeSupport) {
        return new LlmAgentFactory.DynamicAgentFactory(Map.of(lane, new LlmAgentFactory.LaneRuntime(model,
                new ToolPassSettings(memoryTokens, maxToolCalls, 0.50, 3, 69632, 4092))),
                clients, toolProfileRegistry(), personaCatalog(), estimator, null, null, routeSupport, new ObjectMapper());
    }

    public static InferenceLaneRegistry inferenceLaneRegistry() {
        return new InferenceLaneRegistry(inferenceLanes());
    }

    public static PrimitiveExecutor primitiveExecutor(ProjectionStore store, ProducerTemplate template) {
        return new PrimitiveExecutor(new ObjectMapper(), template, personaCatalog(), toolProfileRegistry(),
                projectionManager(store));
    }

    public static AgentRunStore defaultAgentRunStore() {
        AgentRunStore store = new AgentRunStore();
        store.putMission(new Mission("mission-1", "Test mission", List.of(), "active", Instant.now()));
        store.putRun(new AgentRun("run-1", "mission-1", PERSONA_A, ROUTINE_A, Map.of("input", Map.of("brief", "Test run input"))));
        return store;
    }

    public static MissionRosterService missionRosterService(AgentRunStore agentRuns, PersonaCatalog personas, RoutineCatalog routines) {
        return new MissionRosterService(agentRuns, personas, routines, new ObjectMapper());
    }

    public static void bindCatalogBeans(DefaultCamelContext context) {
        context.getRegistry().bind("personaCatalog", personaCatalog());
        context.getRegistry().bind("routineCatalog", routineCatalog());
    }

    public static ProjectionStore projectionStore() {
        return new ProjectionStore();
    }

    public static AgentRunProjectionManager projectionManager(ProjectionStore store) {
        AgentRunProjectionManager manager = new AgentRunProjectionManager();
        set(manager, "store", store);
        set(manager, "personas", personaCatalog());
        set(manager, "routines", routineCatalog());
        return manager;
    }

    public static Exchange exchangeWithRun(String personaId, String routineId) {
        DefaultExchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.setProperty("mission", new Mission("mission-1", "Test mission", List.of(), "active", Instant.now()));
        exchange.setProperty("agentRun", new AgentRun("run-1", "mission-1", personaId, routineId, Map.of("input", Map.of("brief", "Test run input"))));
        exchange.setProperty("routine", routineCatalog().getRequired(routineId));
        return exchange;
    }

    public static LlmJobFactory jobFactory(InferenceLaneRegistry lanes, String defaultModel, boolean providerSchemaDefault) {
        var jobs = new LlmJobFactory();
        set(jobs, "lanes", lanes);
        set(jobs, "personas", personaCatalog());
        set(jobs, "defaultLane", "low-pass");
        set(jobs, "defaultModel", defaultModel);
        set(jobs, "providerSchemaDefault", providerSchemaDefault);
        return jobs;
    }

    public static LlmJobFactory jobFactory() {
        return jobFactory(inferenceLaneRegistry(), "test-model", false);
    }

    public static void restoreJob(Exchange exchange, LlmJobEnvelope job) {
        exchange.setProperty("llmJob", job);
        jobFactory().restore(exchange);
    }

    public static Exchange agentExchange(String laneId, LlmPassType passType) {
        var exchange = new DefaultExchange(new DefaultCamelContext());
        restoreJob(exchange, queuedJob(new LlmJobEnvelope(null, laneId, passType, "test", "test", 0,
                "Inspect", "system", null, Map.of("personaId", PERSONA_A), null)));
        return exchange;
    }

    public static LlmJobEnvelope queuedJob(LlmJobEnvelope request) {
        return new LlmJobEnvelope(request.jobId() == null ? UUID.randomUUID() : request.jobId(), LlmJobStatus.QUEUED,
                request.laneId(), request.passType(), request.requester(), request.role(), request.priority(),
                request.prompt(), request.systemPrompt(), request.responseSchema(), request.metadata(),
                request.requestedAt() == null ? Instant.now() : request.requestedAt());
    }

    public static LlmJobResult llmResult(String laneId, String content) {
        Instant now = Instant.now();
        return new LlmJobResult(UUID.randomUUID(), LlmJobStatus.SUCCEEDED, laneId, LlmPassType.SYNTHESIS, "test", "test", "mock", content, content, now, now, now, 0, null);
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


}
