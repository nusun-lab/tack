package us.nusun.llmshare.framework.tests.support;

import java.util.List;
import java.util.Map;

/** Declarative input and expectations for one bounded routine execution. */
public record ContractScenario(
        String scenario,
        Given given,
        When when,
        Then then) {

    public record Given(Plane plane, Gitea gitea) {
    }

    public record Plane(Map<String, Implementation> implementations) {
    }

    public record Implementation(String state, Handoff handoff) {
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

    public record When(String routine, String entrypoint) {
    }

    public record Then(
            List<PullRequestExpectation> eventually,
            List<String> never,
            Limits limits) {
    }

    public record PullRequestExpectation(String pullRequest, String state) {
    }

    public record Limits(Integer productiveIterations) {
    }
}
