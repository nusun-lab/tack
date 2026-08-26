package us.nusun.llmshare.example.routine;

import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.tests.support.ContractExecution;
import us.nusun.llmshare.framework.tests.support.ContractScenario;
import us.nusun.llmshare.framework.tests.support.ContractScenarioAssertions;
import us.nusun.llmshare.framework.tests.support.ContractScenarioLoader;

class DirectorIntegrationWorkflowContractTest {
    @Test
    void satisfiesDeclarativeRoutineContract() throws Exception {
        ContractScenario scenario = ContractScenarioLoader.load(
                "/contracts/director-drains-integration-ready-work.yaml");

        ContractExecution execution = new RoutineContractHarness().execute(scenario);

        ContractScenarioAssertions.assertSatisfied(execution);
    }
}
