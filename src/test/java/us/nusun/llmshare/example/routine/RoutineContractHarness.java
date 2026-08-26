package us.nusun.llmshare.example.routine;

import org.apache.camel.Exchange;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.model.SetPropertyDefinition;
import us.nusun.llmshare.example.tool.IntegrationToolProfile;
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
import us.nusun.llmshare.framework.tests.support.ContractLlmDriver;
import us.nusun.llmshare.framework.tests.support.ContractPrimitiveBehavior;
import us.nusun.llmshare.framework.tests.support.ContractScenario;
import us.nusun.llmshare.framework.tests.support.ContractWorld;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;
import us.nusun.llmshare.framework.tests.support.TestCamelContexts;

import java.util.List;

/** Executes declarative contracts through registered production routine entrypoints. */
final class RoutineContractHarness {
    private static final String DIRECTOR_ROUTINE = "software-team/director";
    private static final String INTEGRATION_LOOP = "integration-loop";

    ContractExecution execute(ContractScenario scenario) throws Exception {
        ContractWorld world = ContractWorld.from(scenario.given());
        ContractPrimitiveBehavior behavior = behaviorFor(scenario, world);
        ContractLlmDriver driver = new ContractLlmDriver(world, behavior);
        String entrypointUri = entrypointUri(scenario.when());

        try (DefaultCamelContext context = context(scenario, world, driver, entrypointUri)) {
            context.start();
            Exchange result = context.createProducerTemplate().request(
                    "direct:contract-routine-entry",
                    RoutineContractHarness::seedRun);
            Throwable routeFailure = result.getException();
            if (routeFailure == null && result.isFailed()) {
                routeFailure = new IllegalStateException("Camel marked the exchange failed without an exception");
            }
            return new ContractExecution(
                    scenario,
                    world,
                    routeFailure,
                    result.getProperty("directorProductiveIterations", Integer.class));
        }
    }

    private static DefaultCamelContext context(
            ContractScenario scenario,
            ContractWorld world,
            ContractLlmDriver driver,
            String entrypointUri) throws Exception {
        DefaultCamelContext context = TestCamelContexts.quiet();
        context.setAutowiredEnabled(false);
        context.getShutdownStrategy().setTimeout(1);
        FrameworkFixtures.bindCatalogBeans(context);

        PrimitiveExecutor executor = FrameworkFixtures.primitiveExecutor(FrameworkFixtures.projectionStore());
        FrameworkFixtures.set(executor, "toolProfiles", new ToolProfileRegistry(List.of(
                ToolProfile.builder(WorkspaceReadonlyToolProfile.ID).build(),
                ToolProfile.builder(IntegrationToolProfile.ID).build())));
        context.addComponent("tack", new TackComponent(executor));

        registerProductionRoutine(context, scenario.when());
        applyStandalonePostfixCounterCompatibility(context, scenario.when(), world);

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
        throw new IllegalArgumentException("No deterministic primitive behavior registered for "
                + scenario.when().routine() + "/" + scenario.when().entrypoint());
    }

    private static void registerProductionRoutine(DefaultCamelContext context, ContractScenario.When when)
            throws Exception {
        if (!DIRECTOR_ROUTINE.equals(when.routine())) {
            throw new IllegalArgumentException("No production routine registered for " + when.routine());
        }

        AgentRunStore agentRuns = FrameworkFixtures.defaultAgentRunStore();
        DirectorRoutine director = new DirectorRoutine();
        FrameworkFixtures.set(director, "agentRunCapacityService", new AgentRunCapacityService(agentRuns));
        FrameworkFixtures.set(director, "missionRosterService", FrameworkFixtures.missionRosterService(
                agentRuns, FrameworkFixtures.personaCatalog(), FrameworkFixtures.routineCatalog()));
        context.addRoutes(director);
    }

    private static String entrypointUri(ContractScenario.When when) {
        if (DIRECTOR_ROUTINE.equals(when.routine()) && INTEGRATION_LOOP.equals(when.entrypoint())) {
            return "direct:director-integration-loop";
        }
        throw new IllegalArgumentException("No production entrypoint registered for "
                + when.routine() + "/" + when.entrypoint());
    }

    /**
     * Standalone-context compatibility boundary, intentionally kept next to Camel
     * bootstrap rather than scenario behavior. At this revision, Camel evaluates
     * the production `${exchangeProperty.directorProductiveIterations++}` Simple
     * expression to null here, removing the property and preventing a second loop
     * iteration. This advice substitutes only the postfix increment operation; the
     * production loop condition, progress guard, choices, and tack endpoints remain
     * unchanged.
     */
    private static void applyStandalonePostfixCounterCompatibility(
            DefaultCamelContext context,
            ContractScenario.When when,
            ContractWorld world) throws Exception {
        if (!DIRECTOR_ROUTINE.equals(when.routine()) || !INTEGRATION_LOOP.equals(when.entrypoint())) {
            return;
        }
        AdviceWith.adviceWith(context, "director-integration-loop", advice -> advice
                .weaveByType(SetPropertyDefinition.class)
                .selectIndex(2)
                .replace()
                .process(exchange -> {
                    Integer iterations = exchange.getProperty("directorProductiveIterations", Integer.class);
                    exchange.setProperty("directorProductiveIterations", iterations + 1);
                }));
        world.record(ContractWorld.EventKind.INFRASTRUCTURE, "standalone_postfix_counter_adapter",
                "director-integration-loop",
                "Replaced only the standalone-incompatible postfix increment; loop semantics remain production-owned");
    }

    private static void seedRun(Exchange exchange) {
        Exchange seeded = FrameworkFixtures.exchangeWithRun(
                FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        exchange.setProperty("mission", seeded.getProperty("mission", Mission.class));
        exchange.setProperty("agentRun", seeded.getProperty("agentRun", AgentRun.class));
    }
}
