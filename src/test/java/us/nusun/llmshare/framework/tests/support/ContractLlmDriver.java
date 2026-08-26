package us.nusun.llmshare.framework.tests.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import us.nusun.llmshare.framework.model.LlmJobEnvelope;
import us.nusun.llmshare.framework.model.LlmJobRequest;
import us.nusun.llmshare.framework.model.LlmJobResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic replacement for direct:llm. Tack primitives and their result
 * parsing remain real; only the model's tool observations and JSON answers are
 * supplied from the contract world.
 */
public final class ContractLlmDriver implements Processor {
    private static final String WORKSPACE_REVIEW = "director-workspace-review";
    private static final String HAS_CANDIDATE = "director-has-integration-candidate";
    private static final String INTEGRATION_MERGE = "director-integration-merge";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ContractWorld world;
    private final List<String> toolActIds = new ArrayList<>();
    private final List<String> auditTrail = new ArrayList<>();

    private ContractWorld.ImplementationChild selectedCandidate;
    private boolean lastMergeMadeProgress;

    public ContractLlmDriver(ContractWorld world) {
        this.world = world;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        LlmJobRequest request = exchange.getMessage().getBody(LlmJobRequest.class);
        String primitive = String.valueOf(request.metadata().get("primitive"));
        String primitiveId = String.valueOf(request.metadata().get("primitiveId"));

        String content = switch (request.passType()) {
            case TOOL -> toolResponse(primitiveId);
            case SYNTHESIS -> synthesisResponse(primitive, primitiveId);
        };

        LlmJobEnvelope job = LlmJobEnvelope.queued(request.normalized());
        exchange.getMessage().setBody(LlmJobResult.succeeded(
                job, "contract-model", content, content, Instant.now()));
    }

    public List<String> toolActIds() {
        return List.copyOf(toolActIds);
    }

    public List<String> auditTrail() {
        return List.copyOf(auditTrail);
    }

    private String toolResponse(String primitiveId) {
        toolActIds.add(primitiveId);
        return switch (primitiveId) {
            case WORKSPACE_REVIEW -> reviewWorkspace();
            case INTEGRATION_MERGE -> mergeSelectedCandidate();
            default -> throw new AssertionError("Unexpected tool primitive: " + primitiveId);
        };
    }

    private String synthesisResponse(String primitive, String primitiveId) throws Exception {
        if ("decide".equals(primitive)) {
            if (!HAS_CANDIDATE.equals(primitiveId)) {
                throw new AssertionError("Unexpected decision primitive: " + primitiveId);
            }
            return json(Map.of(
                    "decisionId", HAS_CANDIDATE,
                    "result", selectedCandidate != null,
                    "confidence", 1.0,
                    "reason", selectedCandidate == null
                            ? "No current integration handoff was selected"
                            : "Selected " + selectedCandidate.id()));
        }
        if (!"act".equals(primitive)) {
            throw new AssertionError("Unexpected synthesis primitive: " + primitive);
        }

        boolean durableProgress = INTEGRATION_MERGE.equals(primitiveId) && lastMergeMadeProgress;
        String summary = switch (primitiveId) {
            case WORKSPACE_REVIEW -> selectedCandidate == null
                    ? "No current integration handoff found"
                    : "Selected " + selectedCandidate.id() + " with " + selectedCandidate.pullRequestId();
            case INTEGRATION_MERGE -> durableProgress
                    ? "Merged " + selectedCandidate.pullRequestId()
                    : selectedCandidate.pullRequestId() + " was already merged";
            default -> throw new AssertionError("Unexpected act synthesis primitive: " + primitiveId);
        };

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", summary);
        result.put("summary", summary);
        result.put("outcome", durableProgress ? "SUCCEEDED" : "NOOP");
        result.put("durableProgress", durableProgress);
        result.put("confidence", 1.0);
        return json(result);
    }

    private String reviewWorkspace() {
        selectedCandidate = world.firstCurrentIntegrationHandoff().orElse(null);
        lastMergeMadeProgress = false;
        if (selectedCandidate == null) {
            auditTrail.add("review -> none");
            return "No In Progress child has a current PM INTEGRATION READY handoff.";
        }
        auditTrail.add("review -> " + selectedCandidate.id());
        return "Selected Plane child %s: In Progress, current PM INTEGRATION READY, linked PR %s (%s)."
                .formatted(
                        selectedCandidate.id(),
                        selectedCandidate.pullRequestId(),
                        world.pullRequestState(selectedCandidate.pullRequestId()));
    }

    private String mergeSelectedCandidate() {
        if (selectedCandidate == null) {
            lastMergeMadeProgress = false;
            auditTrail.add("merge -> none");
            return "No candidate selected; no mutation.";
        }
        lastMergeMadeProgress = world.merge(selectedCandidate.pullRequestId());
        auditTrail.add("merge " + selectedCandidate.pullRequestId()
                + (lastMergeMadeProgress ? " -> MERGED" : " -> NOOP"));
        return lastMergeMadeProgress
                ? "Merged " + selectedCandidate.pullRequestId() + "; durable external state changed."
                : selectedCandidate.pullRequestId() + " is already merged; no external state changed.";
    }

    private String json(Map<String, ?> value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
