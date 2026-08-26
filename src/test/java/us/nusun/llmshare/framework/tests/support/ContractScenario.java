package us.nusun.llmshare.framework.tests.support;

import java.util.List;
import java.util.Map;

/** Declarative input and expectations for one bounded routine execution. */
public record ContractScenario(
        String scenario,
        Given given,
        When when,
        Then then) {

    public record Given(Workspace workspace, Plane plane, Gitea gitea, Source source) {
    }

    public record Workspace(
            String plane,
            String source,
            String documentation,
            String teamChat) {
    }

    public record Plane(Map<String, Implementation> implementations) {
    }

    public record Implementation(
            String state,
            String assignee,
            String activity,
            DeliveryContext deliveryContext,
            Handoff handoff,
            Map<String, PlaneComment> comments) {
    }

    public record DeliveryContext(
            String parentAcceptanceReference,
            String repository,
            String dependencyContext,
            String expectedReviewEvidence) {
    }

    public record Handoff(
            String type,
            Boolean current,
            String pullRequest,
            String repository,
            String reviewedHead,
            String reviewedBase,
            String reviewedAt,
            String validation,
            String acceptanceReference) {
    }

    public record PlaneComment(
            String requiredOwner,
            String gap,
            String evidence) {
    }

    public record Gitea(Map<String, PullRequest> pullRequests) {
    }

    public record PullRequest(
            String state,
            Boolean mergeable,
            Boolean conflicted,
            String repository,
            String head,
            String base) {
    }

    public record Source(Map<String, SourceRepository> repositories) {
    }

    public record SourceRepository(
            String revision,
            String defaultBranch,
            Map<String, String> branches,
            Map<String, String> files) {
    }

    public record When(String routine, String entrypoint) {
    }

    public record Then(
            List<StateExpectation> eventually,
            List<String> unchanged,
            List<EventExpectation> never,
            List<Limit> limits) {
    }

    public record StateExpectation(String path, Object equals) {
    }

    public record EventExpectation(String event) {
    }

    public record Limit(String metric, Integer atMost) {
    }
}
