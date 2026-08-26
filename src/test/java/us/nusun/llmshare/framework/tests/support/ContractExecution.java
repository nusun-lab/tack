package us.nusun.llmshare.framework.tests.support;

import java.util.Map;

/** Captured result of executing one scenario through its production Camel entrypoint. */
public record ContractExecution(
        ContractScenario scenario,
        ContractWorld world,
        Map<String, Object> initialWorldState,
        Throwable routeFailure,
        Map<String, Integer> metrics) {
}
