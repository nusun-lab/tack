package us.nusun.llmshare.example.routine;

import org.apache.camel.Exchange;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.model.SetPropertyDefinition;
import org.junit.jupiter.api.Test;
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
import us.nusun.llmshare.framework.tests.support.ContractLlmDriver;
import us.nusun.llmshare.framework.tests.support.ContractWorld;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;
import us.nusun.llmshare.framework.tests.support.TestCamelContexts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectorIntegrationWorkflowContractTest {
    @Test
    void productiveStickyLoopMergesEveryCurrentIntegrationCandidate() throws Exception {
        ContractWorld world = new ContractWorld()
                .integrationReady("A1", "PR1")
                .integrationReady("A2", "PR2");
        ContractLlmDriver driver = new ContractLlmDriver(world);

        try (DefaultCamelContext context = context(driver)) {
            context.start();

            Exchange result = context.createProducerTemplate().request(
                    "direct:contract-director-integration-loop",
                    DirectorIntegrationWorkflowContractTest::seedRun);

            assertFalse(result.isFailed(), () -> "Production route failed: " + result.getException());
            assertTrue(driver.toolActIds().size() >= 4,
                    () -> "Expected the production sticky loop to repeat; audit=" + driver.auditTrail());
            assertEquals(List.of(
                    "director-workspace-review",
                    "director-integration-merge",
                    "director-workspace-review",
                    "director-integration-merge"), driver.toolActIds().subList(0, 4));

            Map<String, ContractWorld.PullRequestState> expected = new LinkedHashMap<>();
            expected.put("PR1", ContractWorld.PullRequestState.MERGED);
            expected.put("PR2", ContractWorld.PullRequestState.MERGED);
            assertEquals(expected, world.pullRequestStates(),
                    () -> "one productive Director sticky loop should merge every ready pull request; route audit="
                            + driver.auditTrail());
        }
    }

    private static DefaultCamelContext context(ContractLlmDriver driver) throws Exception {
        DefaultCamelContext context = TestCamelContexts.quiet();
        context.setAutowiredEnabled(false);
        context.getShutdownStrategy().setTimeout(1);
        FrameworkFixtures.bindCatalogBeans(context);

        PrimitiveExecutor executor = FrameworkFixtures.primitiveExecutor(FrameworkFixtures.projectionStore());
        FrameworkFixtures.set(executor, "toolProfiles", new ToolProfileRegistry(List.of(
                ToolProfile.builder(WorkspaceReadonlyToolProfile.ID).build(),
                ToolProfile.builder(IntegrationToolProfile.ID).build())));
        context.addComponent("tack", new TackComponent(executor));

        AgentRunStore agentRuns = FrameworkFixtures.defaultAgentRunStore();
        DirectorRoutine director = new DirectorRoutine();
        FrameworkFixtures.set(director, "agentRunCapacityService", new AgentRunCapacityService(agentRuns));
        FrameworkFixtures.set(director, "missionRosterService", FrameworkFixtures.missionRosterService(
                agentRuns, FrameworkFixtures.personaCatalog(), FrameworkFixtures.routineCatalog()));
        context.addRoutes(director);

        // In this standalone Camel context the production postfix Simple expression
        // evaluates to null and removes the loop counter. Replace only that mechanical
        // increment so the production progress guard, loop, choices, and primitives run.
        AdviceWith.adviceWith(context, "director-integration-loop", advice -> advice
                .weaveByType(SetPropertyDefinition.class)
                .selectIndex(2)
                .replace()
                .process(exchange -> {
                    Integer iterations = exchange.getProperty("directorProductiveIterations", Integer.class);
                    exchange.setProperty("directorProductiveIterations", iterations + 1);
                }));

        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:llm").routeId("contract-llm-driver").process(driver);

                from("direct:contract-director-integration-loop")
                        .routeId("contract-director-integration-loop-entry")
                        .setHeader(TackHeaders.THOUGHT_PURPOSE,
                                constant("Track Director integration candidates and merge outcomes."))
                        .to("tack:thought-enter?name=contract-integration&maxTokens=300")
                        .to("direct:director-integration-loop");
            }
        });
        return context;
    }

    private static void seedRun(Exchange exchange) {
        Exchange seeded = FrameworkFixtures.exchangeWithRun(
                FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        exchange.setProperty("mission", seeded.getProperty("mission", Mission.class));
        exchange.setProperty("agentRun", seeded.getProperty("agentRun", AgentRun.class));
    }
}
