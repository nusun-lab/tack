package us.nusun.llmshare.framework.tests.support;

/** Test-runtime stop used to bound a production route that fails to terminate. */
public final class ContractLimitExceededException extends RuntimeException {
    public ContractLimitExceededException(String message) {
        super(message);
    }
}
