package us.nusun.llmshare.framework.tests.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import us.nusun.llmshare.framework.model.LlmJobEnvelope;
import us.nusun.llmshare.framework.model.LlmJobRequest;
import us.nusun.llmshare.framework.model.LlmJobResult;

import java.time.Instant;
import java.util.Map;

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
                primitiveId, "primitive=%s lane=%s requester=%s role=%s"
                        .formatted(primitive, request.laneId(), request.requester(), request.role()));

        String content = switch (request.passType()) {
            case TOOL -> behavior.toolResponse(request);
            case SYNTHESIS -> objectMapper.writeValueAsString(
                    "thought-exit".equals(primitive)
                            ? thoughtConsolidation(primitiveId)
                            : behavior.synthesisResponse(request));
        };

        LlmJobEnvelope job = LlmJobEnvelope.queued(request.normalized());
        exchange.getMessage().setBody(LlmJobResult.succeeded(
                job, "contract-model", content, content, Instant.now()));
    }

    private Map<String, ?> thoughtConsolidation(String thoughtName) {
        world.record(ContractWorld.EventKind.MODEL, "deterministic_thought_consolidation",
                thoughtName, "Preserved contract observations in the parent Tack thought");
        return Map.of(
                "content", "Deterministic contract observations for " + thoughtName + " were preserved.");
    }
}
