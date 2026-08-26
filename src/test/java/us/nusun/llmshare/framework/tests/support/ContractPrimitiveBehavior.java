package us.nusun.llmshare.framework.tests.support;

import java.util.Map;

/** Replaceable behavior behind the deterministic direct:llm adapter. */
public interface ContractPrimitiveBehavior {
    String toolResponse(String primitiveId);

    Map<String, ?> synthesisResponse(String primitive, String primitiveId);
}
