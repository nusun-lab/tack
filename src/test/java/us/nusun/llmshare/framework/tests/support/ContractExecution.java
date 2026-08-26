package us.nusun.llmshare.framework.tests.support;

/** Captured result of executing one scenario through its production Camel entrypoint. */
public record ContractExecution(
        ContractScenario scenario,
        ContractWorld world,
        Throwable routeFailure,
        Integer productiveIterations) {
}
