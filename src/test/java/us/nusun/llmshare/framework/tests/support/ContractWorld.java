package us.nusun.llmshare.framework.tests.support;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal in-memory model of the external state used by workflow contract tests.
 */
public final class ContractWorld {
    public enum PlaneState {
        IN_PROGRESS,
        DONE
    }

    public enum PullRequestState {
        OPEN,
        MERGED
    }

    public static final class ImplementationChild {
        private final String id;
        private final String pullRequestId;
        private PlaneState planeState;
        private boolean currentIntegrationReadyHandoff;

        private ImplementationChild(String id, String pullRequestId) {
            this.id = id;
            this.pullRequestId = pullRequestId;
            this.planeState = PlaneState.IN_PROGRESS;
            this.currentIntegrationReadyHandoff = true;
        }

        public String id() {
            return id;
        }

        public String pullRequestId() {
            return pullRequestId;
        }

        public PlaneState planeState() {
            return planeState;
        }

        public boolean currentIntegrationReadyHandoff() {
            return currentIntegrationReadyHandoff;
        }
    }

    private final LinkedHashMap<String, ImplementationChild> children = new LinkedHashMap<>();
    private final LinkedHashMap<String, PullRequestState> pullRequests = new LinkedHashMap<>();

    public ContractWorld integrationReady(String childId, String pullRequestId) {
        children.put(childId, new ImplementationChild(childId, pullRequestId));
        pullRequests.put(pullRequestId, PullRequestState.OPEN);
        return this;
    }

    /**
     * Models the selection contract currently expressed by DirectorRoutine: Plane is
     * In Progress and its current PM handoff is INTEGRATION READY. Deliberately, that
     * production contract does not filter on the linked pull request's current state.
     */
    public Optional<ImplementationChild> firstCurrentIntegrationHandoff() {
        return children.values().stream()
                .filter(child -> child.planeState() == PlaneState.IN_PROGRESS)
                .filter(ImplementationChild::currentIntegrationReadyHandoff)
                .findFirst();
    }

    public PullRequestState pullRequestState(String pullRequestId) {
        return pullRequests.get(pullRequestId);
    }

    public boolean merge(String pullRequestId) {
        if (pullRequests.get(pullRequestId) != PullRequestState.OPEN) {
            return false;
        }
        pullRequests.put(pullRequestId, PullRequestState.MERGED);
        return true;
    }

    public Map<String, PullRequestState> pullRequestStates() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(pullRequests));
    }
}
