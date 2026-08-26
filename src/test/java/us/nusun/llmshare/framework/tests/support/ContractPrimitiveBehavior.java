package us.nusun.llmshare.framework.tests.support;

import us.nusun.llmshare.framework.model.LlmJobRequest;

import java.util.Map;

/** Replaceable behavior behind the deterministic direct:llm adapter. */
public interface ContractPrimitiveBehavior {
    String toolResponse(LlmJobRequest request);

    Map<String, ?> synthesisResponse(LlmJobRequest request);

    default String primitive(LlmJobRequest request) {
        return String.valueOf(request.metadata().get("primitive"));
    }

    default String primitiveId(LlmJobRequest request) {
        return String.valueOf(request.metadata().get("primitiveId"));
    }
}
