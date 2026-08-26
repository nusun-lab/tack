package us.nusun.llmshare.example.routine;

import org.apache.camel.Exchange;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.model.SetPropertyDefinition;
import us.nusun.llmshare.example.tool.IntegrationToolProfile;
import us.nusun.llmshare.example.tool.ProjectManagementToolProfile;
import us.nusun.llmshare.example.tool.SourceControlToolProfile;
import us.nusun.llmshare.example.tool.SourceControlWriteToolProfile;
import us.nusun.llmshare.example.tool.TeamChatToolProfile;
import us.nusun.llmshare.example.tool.WorkspaceReadonlyToolProfile;
import us.nusun.llmshare.framework.inventory.ToolProfileRegistry;
import us.nusun.llmshare.framework.model.Mission;
import us.nusun.llmshare.framework.model.ToolProfile;
import us.nusun.llmshare.framework.service.AgentRunCapacityService;
import us.nusun.llmshare.framework.state.AgentRun;
import us.nusun.llmshare.framework.state.AgentRunStore;
import us.nusun.llmshare.framework.tack.PrimitiveExecutor;
import us.nusun.llmshare.framework.tack.TackComponent;
import us.nusun.llmshare.framework.tack.TackHeaders;
import us.nusun.llmshare.framework.tests.support.ContractExecution;
import us.nusun.llmshare.framework.tests.support.ContractLimitExceededException;
import us.nusun.llmshare.framework.tests.support.ContractLlmDriver;
import us.nusun.llmshare.framework.tests.support.ContractPrimitiveBehavior;
import us.nusun.llmshare.framework.tests.support.ContractScenario;
import us.nusun.llmshare.framework.tests.support.ContractWorld;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;
import us.nusun.llmshare.framework.tests.support.TestCamelContexts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Executes declarative contracts through registered production routine entrypoints. */
final class RoutineContractHarness {
    private static final String DIRECTOR_ROUTINE = "software-team/director";
    private static final String DEVELOPER_ROUTINE = "software-team/application-developer";
    private static final String INTEGRATION_LOOP = "integration-loop";
    private static final String IMPLEMENTATION = "implementation";

    ContractExecution execute(ContractScenario scenario) throws Exception {
        ContractWorld world = ContractWorld.from(scenario.given());
        ContractPrimitiveBehavior behavior = behaviorFor(scenario, world);
        ContractLlmDriver driver = new ContractLlmDriver(world, behavior);
        String entrypointUri = entrypointUri(scenario.when());
        Map<String, Object> initialWorldState = world.snapshot();
        Map<String, Integer> observedMetrics = new LinkedHashMap<>();

        try (DefaultCamelContext context = context(scenario, world, driver, entrypointUri, observedMetrics)) {
            context.start();
            Exchange result = null;
            Throwable routeFailure = null;
            try {
                result = context.createProducerTemplate().request(
                        "direct:contract-routine-entry",
                        RoutineContractHarness::seedRun);
                routeFailure = result.getException();
                if (routeFailure == null && result.isFailed()) {
                    routeFailure = new IllegalStateException("Camel marked the exchange failed without an exception");
                }
            } catch (Exception failure) {
                routeFailure = failure;
            }
            return new ContractExecution(
                    scenario,
                    world,
                    initialWorldState,
                    routeFailure,
                    metrics(scenario.when(), result, observedMetrics));
        }
    }

    private static DefaultCamelContext context(
            ContractScenario scenario,
            ContractWorld world,
            ContractLlmDriver driver,
            String entrypointUri,
            Map<String, Integer> observedMetrics) throws Exception {
        DefaultCamelContext context = TestCamelContexts.quiet();
        context.setAutowiredEnabled(false);
        context.getShutdownStrategy().setTimeout(1);
        FrameworkFixtures.bindCatalogBeans(context);

        PrimitiveExecutor executor = FrameworkFixtures.primitiveExecutor(FrameworkFixtures.projectionStore());
        FrameworkFixtures.set(executor, "toolProfiles", new ToolProfileRegistry(List.of(
                ToolProfile.builder(WorkspaceReadonlyToolProfile.ID).build(),
                ToolProfile.builder(IntegrationToolProfile.ID).build(),
                ToolProfile.builder(ProjectManagementToolProfile.ID).build(),
                ToolProfile.builder(SourceControlToolProfile.ID).build(),
                ToolProfile.builder(SourceControlWriteToolProfile.ID).build(),
                ToolProfile.builder(TeamChatToolProfile.ID).build())));
        context.addComponent("tack", new TackComponent(executor));

        registerProductionRoutine(context, scenario.when());
        applyStandalonePostfixCounterCompatibility(context, scenario, world, observedMetrics);

        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:llm").routeId("contract-llm-driver").process(driver);

                from("direct:contract-routine-entry")
                        .routeId("contract-routine-entry")
                        .setHeader(TackHeaders.THOUGHT_PURPOSE,
                                constant("Track routine contract reads, decisions, and durable outcomes."))
                        .to("tack:thought-enter?name=contract-scenario&maxTokens=300")
                        .to(entrypointUri);
            }
        });
        return context;
    }

    private static ContractPrimitiveBehavior behaviorFor(ContractScenario scenario, ContractWorld world) {
        if (DIRECTOR_ROUTINE.equals(scenario.when().routine())
                && INTEGRATION_LOOP.equals(scenario.when().entrypoint())) {
            return new DirectorIntegrationPrimitiveBehavior(world);
        }
        if (DEVELOPER_ROUTINE.equals(scenario.when().routine())
                && IMPLEMENTATION.equals(scenario.when().entrypoint())) {
            return new ApplicationDeveloperInvalidContextPrimitiveBehavior(world);
        }
        throw new IllegalArgumentException("No deterministic primitive behavior registered for "
                + scenario.when().routine() + "/" + scenario.when().entrypoint());
    }

    private static void registerProductionRoutine(DefaultCamelContext context, ContractScenario.When when)
            throws Exception {
        if (DIRECTOR_ROUTINE.equals(when.routine())) {
            AgentRunStore agentRuns = FrameworkFixtures.defaultAgentRunStore();
            DirectorRoutine director = new DirectorRoutine();
            FrameworkFixtures.set(director, "agentRunCapacityService", new AgentRunCapacityService(agentRuns));
            FrameworkFixtures.set(director, "missionRosterService", FrameworkFixtures.missionRosterService(
                    agentRuns, FrameworkFixtures.personaCatalog(), FrameworkFixtures.routineCatalog()));
            context.addRoutes(director);
            return;
        }
        if (DEVELOPER_ROUTINE.equals(when.routine())) {
            context.addRoutes(new ApplicationDeveloperRoutine());
            return;
        }
        throw new IllegalArgumentException("No production routine registered for " + when.routine());
    }

    private static String entrypointUri(ContractScenario.When when) {
        if (DIRECTOR_ROUTINE.equals(when.routine()) && INTEGRATION_LOOP.equals(when.entrypoint())) {
            return "direct:director-integration-loop";
        }
        if (DEVELOPER_ROUTINE.equals(when.routine()) && IMPLEMENTATION.equals(when.entrypoint())) {
            return "direct:example-software-team-application-developer-implementation";
        }
        throw new IllegalArgumentException("No production entrypoint registered for "
                + when.routine() + "/" + when.entrypoint());
    }

    /**
     * Standalone-context compatibility boundary, intentionally kept next to Camel
     * bootstrap rather than scenario behavior. At this revision, the standalone
     * Camel context evaluates production postfix Simple counter expressions to null,
     * removing their properties. This advice substitutes only those increments; the
     * production loop conditions, progress guards, choices, and tack endpoints remain
     * unchanged. The replacement processor also enforces each scenario's declared
     * counter limit so a defective production loop cannot hang the test process.
     */
    private static void applyStandalonePostfixCounterCompatibility(
            DefaultCamelContext context,
            ContractScenario scenario,
            ContractWorld world,
            Map<String, Integer> observedMetrics) throws Exception {
        ContractScenario.When when = scenario.when();
        if (DIRECTOR_ROUTINE.equals(when.routine()) && INTEGRATION_LOOP.equals(when.entrypoint())) {
            replacePostfixIncrement(
                    context, "director-integration-loop", 2, "directorProductiveIterations",
                    "productiveIterations", limit(scenario, "productiveIterations"), world, observedMetrics);
        } else if (DEVELOPER_ROUTINE.equals(when.routine()) && IMPLEMENTATION.equals(when.entrypoint())) {
            replacePostfixIncrement(
                    context, "dev-delivery-loop", 2, "devProductiveIterations",
                    "deliveryIterations", limit(scenario, "deliveryIterations"), world, observedMetrics);
            replacePostfixIncrement(
                    context, "dev-implementation-loop", 3, "developerImplementationCycle",
                    "implementationIterations", limit(scenario, "implementationIterations"), world, observedMetrics);
        }
    }

    private static void replacePostfixIncrement(
            DefaultCamelContext context,
            String routeId,
            int setPropertyIndex,
            String property,
            String metric,
            int atMost,
            ContractWorld world,
            Map<String, Integer> observedMetrics) throws Exception {
        AdviceWith.adviceWith(context, routeId, advice -> advice
                .weaveByType(SetPropertyDefinition.class)
                .selectIndex(setPropertyIndex)
                .replace()
                .process(exchange -> {
                    Integer iterations = exchange.getProperty(property, Integer.class);
                    int next = iterations + 1;
                    exchange.setProperty(property, next);
                    observedMetrics.put(metric, next);
                    if (next > atMost) {
                        world.record(ContractWorld.EventKind.INFRASTRUCTURE, "execution_limit_exceeded",
                                metric, "actual=" + next + " atMost=" + atMost);
                        throw new ContractLimitExceededException("Contract execution metric " + metric + '=' + next
                                + " exceeded limit " + atMost);
                    }
                }));
        world.record(ContractWorld.EventKind.INFRASTRUCTURE, "standalone_postfix_counter_adapter",
                routeId,
                "Replaced only the standalone-incompatible postfix increment for " + property
                        + "; production control flow remains unchanged and the declared limit is test-enforced");
    }

    private static int limit(ContractScenario scenario, String metric) {
        return scenario.then().limits().stream()
                .filter(limit -> metric.equals(limit.metric()))
                .map(ContractScenario.Limit::atMost)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No contract limit declared for metric " + metric));
    }

    private static Map<String, Integer> metrics(
            ContractScenario.When when,
            Exchange exchange,
            Map<String, Integer> observedMetrics) {
        LinkedHashMap<String, Integer> metrics = new LinkedHashMap<>(observedMetrics);
        if (exchange != null && DIRECTOR_ROUTINE.equals(when.routine())) {
            putMetric(metrics, "productiveIterations",
                    exchange.getProperty("directorProductiveIterations", Integer.class));
        } else if (exchange != null && DEVELOPER_ROUTINE.equals(when.routine())) {
            putMetric(metrics, "deliveryIterations",
                    exchange.getProperty("devProductiveIterations", Integer.class));
            putMetric(metrics, "implementationIterations",
                    exchange.getProperty("developerImplementationCycle", Integer.class));
        }
        return Map.copyOf(metrics);
    }

    private static void putMetric(Map<String, Integer> metrics, String name, Integer value) {
        if (value != null) {
            metrics.put(name, value);
        }
    }

    private static void seedRun(Exchange exchange) {
        Exchange seeded = FrameworkFixtures.exchangeWithRun(
                FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        exchange.setProperty("mission", seeded.getProperty("mission", Mission.class));
        exchange.setProperty("agentRun", seeded.getProperty("agentRun", AgentRun.class));
    }
}
