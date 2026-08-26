package us.nusun.llmshare.example.routine;

import us.nusun.llmshare.framework.tests.support.ContractPrimitiveBehavior;
import us.nusun.llmshare.framework.tests.support.ContractWorld;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministic model interpretation of the current Director integration prompts.
 * Selection policy lives here, not in the external-fact model.
 */
final class DirectorIntegrationPrimitiveBehavior implements ContractPrimitiveBehavior {
    private static final String WORKSPACE_REVIEW = "director-workspace-review";
    private static final String HAS_CANDIDATE = "director-has-integration-candidate";
    private static final String INTEGRATION_MERGE = "director-integration-merge";

    private final ContractWorld world;
    private String selectedImplementationId;
    private ContractWorld.MutationResult lastMerge = new ContractWorld.MutationResult(
            false, "no_merge_attempted", "No merge has been attempted");
    private String lastReviewSummary = "No workspace review has run";

    DirectorIntegrationPrimitiveBehavior(ContractWorld world) {
        this.world = world;
    }

    @Override
    public String toolResponse(String primitiveId) {
        return switch (primitiveId) {
            case WORKSPACE_REVIEW -> reviewWorkspace();
            case INTEGRATION_MERGE -> mergeSelectedPullRequest();
            default -> throw new AssertionError("Unexpected Director tool primitive: " + primitiveId);
        };
    }

    @Override
    public Map<String, ?> synthesisResponse(String primitive, String primitiveId) {
        if ("decide".equals(primitive)) {
            if (!HAS_CANDIDATE.equals(primitiveId)) {
                throw new AssertionError("Unexpected Director decision primitive: " + primitiveId);
            }
            boolean result = selectedImplementationId != null;
            world.record(ContractWorld.EventKind.DECISION, "director.has_integration_candidate",
                    selectedImplementationId, "result=" + result);
            return Map.of(
                    "decisionId", HAS_CANDIDATE,
                    "result", result,
                    "confidence", 1.0,
                    "reason", result
                            ? "The review selected " + selectedImplementationId
                            : "The review found no current integration-ready handoff");
        }
        if (!"act".equals(primitive)) {
            throw new AssertionError("Unexpected synthesis primitive: " + primitive);
        }

        String summary;
        boolean durableProgress;
        if (WORKSPACE_REVIEW.equals(primitiveId)) {
            summary = lastReviewSummary;
            durableProgress = false;
        } else if (INTEGRATION_MERGE.equals(primitiveId)) {
            summary = lastMerge.detail();
            durableProgress = lastMerge.durableProgress();
        } else {
            throw new AssertionError("Unexpected Director act synthesis primitive: " + primitiveId);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", summary);
        result.put("summary", summary);
        result.put("outcome", durableProgress ? "SUCCEEDED" : "NOOP");
        result.put("durableProgress", durableProgress);
        result.put("confidence", 1.0);
        return result;
    }

    private String reviewWorkspace() {
        // This is the current prompt's selection rule. In particular, it does not
        // inspect linked PR state; that omission is what the contract exposes.
        selectedImplementationId = world.readImplementations().stream()
                .filter(implementation -> implementation.state() == ContractWorld.PlaneState.IN_PROGRESS)
                .filter(implementation -> implementation.handoff() != null)
                .filter(implementation -> implementation.handoff().current())
                .filter(implementation -> "integration_ready".equals(implementation.handoff().type()))
                .filter(implementation -> complete(implementation.handoff()))
                .map(ContractWorld.Implementation::id)
                .findFirst()
                .orElse(null);
        lastMerge = new ContractWorld.MutationResult(false, "no_merge_attempted", "No merge has been attempted");

        if (selectedImplementationId == null) {
            lastReviewSummary = "No In Progress implementation has a current INTEGRATION READY handoff.";
            world.record(ContractWorld.EventKind.DECISION, "director.select_integration_candidate",
                    null, "none selected");
            return lastReviewSummary;
        }

        ContractWorld.Implementation selected = world.readImplementation(selectedImplementationId).orElseThrow();
        ContractWorld.PullRequest pullRequest = world.readPullRequest(selected.handoff().pullRequestId()).orElseThrow();
        world.record(ContractWorld.EventKind.DECISION, "director.select_integration_candidate",
                selectedImplementationId, "selected linked pull request " + pullRequest.id());
        lastReviewSummary = "Selected Plane child %s: state=%s, current handoff=%s, linked PR %s state=%s."
                .formatted(selected.id(), selected.state(), selected.handoff().type(),
                        pullRequest.id(), pullRequest.state());
        return lastReviewSummary;
    }

    private String mergeSelectedPullRequest() {
        if (selectedImplementationId == null) {
            lastMerge = new ContractWorld.MutationResult(false, "no_candidate_selected", "No candidate selected");
            world.record(ContractWorld.EventKind.NOOP, lastMerge.outcome(), null, lastMerge.detail());
            return lastMerge.detail();
        }
        ContractWorld.Implementation selected = world.readImplementation(selectedImplementationId).orElseThrow();
        ContractWorld.PullRequest pullRequest = world.readPullRequest(selected.handoff().pullRequestId()).orElseThrow();
        if (!matchesReviewedSurface(selected.handoff(), pullRequest)) {
            lastMerge = new ContractWorld.MutationResult(
                    false, "reviewed_surface_changed", "Current pull request no longer matches the reviewed surface");
            world.record(ContractWorld.EventKind.NOOP, lastMerge.outcome(), pullRequest.id(), lastMerge.detail());
            return lastMerge.detail();
        }
        lastMerge = world.mergePullRequest(pullRequest.id());
        return lastMerge.detail();
    }

    private static boolean complete(ContractWorld.Handoff handoff) {
        return text(handoff.pullRequestId())
                && text(handoff.repository())
                && text(handoff.reviewedHead())
                && text(handoff.reviewedBase())
                && text(handoff.reviewedAt())
                && text(handoff.validation())
                && text(handoff.acceptanceReference());
    }

    private static boolean matchesReviewedSurface(
            ContractWorld.Handoff handoff,
            ContractWorld.PullRequest pullRequest) {
        return handoff.repository().equals(pullRequest.repository())
                && handoff.reviewedHead().equals(pullRequest.head())
                && handoff.reviewedBase().equals(pullRequest.base());
    }

    private static boolean text(String value) {
        return value != null && !value.isBlank();
    }
}
