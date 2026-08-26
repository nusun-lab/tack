package us.nusun.llmshare.framework.tests.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import us.nusun.llmshare.framework.model.LlmJobEnvelope;
import us.nusun.llmshare.framework.model.LlmJobRequest;
import us.nusun.llmshare.framework.model.LlmJobResult;

import java.time.Instant;

/**
 * Generic deterministic replacement for direct:llm. Tack still builds requests,
 * runs both passes, parses structured output, and creates real StepResults.
 */
public final class ContractLlmDriver implements Processor {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ContractWorld world;
    private final ContractPrimitiveBehavior behavior;

    public ContractLlmDriver(ContractWorld world, ContractPrimitiveBehavior behavior) {
        this.world = world;
        this.behavior = behavior;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        LlmJobRequest request = exchange.getMessage().getBody(LlmJobRequest.class);
        String primitive = String.valueOf(request.metadata().get("primitive"));
        String primitiveId = String.valueOf(request.metadata().get("primitiveId"));
        world.record(ContractWorld.EventKind.MODEL, "deterministic_llm_" + request.passType().name().toLowerCase(),
                primitiveId, "primitive=" + primitive);

        String content = switch (request.passType()) {
            case TOOL -> behavior.toolResponse(primitiveId);
            case SYNTHESIS -> objectMapper.writeValueAsString(behavior.synthesisResponse(primitive, primitiveId));
        };

        LlmJobEnvelope job = LlmJobEnvelope.queued(request.normalized());
        exchange.getMessage().setBody(LlmJobResult.succeeded(
                job, "contract-model", content, content, Instant.now()));
    }
}
