package us.nusun.llmshare.framework.tests.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory external facts plus mechanical read/mutation operations.
 *
 * <p>This type deliberately has no notion of Director candidates or selection
 * policy. A deterministic model (or, later, fake external tools) interprets the
 * facts exposed here.</p>
 */
public final class ContractWorld {
    public enum PlaneState {
        IN_PROGRESS,
        DONE
    }

    public enum PullRequestState {
        OPEN,
        CLOSED,
        MERGED
    }

    public enum EventKind {
        READ,
        DECISION,
        MUTATION_ATTEMPT,
        MUTATION_SUCCEEDED,
        NOOP,
        MODEL,
        INFRASTRUCTURE
    }

    public record Handoff(
            String type,
            boolean current,
            String pullRequestId,
            String repository,
            String reviewedHead,
            String reviewedBase,
            String reviewedAt,
            String validation,
            String acceptanceReference) {
    }

    public record Implementation(String id, PlaneState state, Handoff handoff) {
    }

    public record PullRequest(
            String id,
            PullRequestState state,
            boolean mergeable,
            boolean conflicted,
            String repository,
            String head,
            String base) {
    }

    public record Event(
            int sequence,
            EventKind kind,
            String action,
            String subject,
            String detail) {
        @Override
        public String toString() {
            String target = subject == null || subject.isBlank() ? "" : " " + subject;
            String explanation = detail == null || detail.isBlank() ? "" : " — " + detail;
            return "%02d %-18s %s%s%s".formatted(sequence, kind, action, target, explanation);
        }
    }

    public record MutationResult(boolean durableProgress, String outcome, String detail) {
    }

    private final LinkedHashMap<String, Implementation> implementations;
    private final LinkedHashMap<String, PullRequest> pullRequests;
    private final List<Event> events = new ArrayList<>();

    private ContractWorld(
            LinkedHashMap<String, Implementation> implementations,
            LinkedHashMap<String, PullRequest> pullRequests) {
        this.implementations = implementations;
        this.pullRequests = pullRequests;
    }

    /** Materializes only facts; no eligibility or ordering policy is applied. */
    public static ContractWorld from(ContractScenario.Given given) {
        LinkedHashMap<String, Implementation> implementations = new LinkedHashMap<>();
        given.plane().implementations().forEach((id, fact) -> {
            ContractScenario.Handoff source = fact.handoff();
            Handoff handoff = source == null ? null : new Handoff(
                    normalized(source.type()),
                    Boolean.TRUE.equals(source.current()),
                    source.pullRequest(),
                    source.repository(),
                    source.reviewedHead(),
                    source.reviewedBase(),
                    source.reviewedAt(),
                    source.validation(),
                    source.acceptanceReference());
            implementations.put(id, new Implementation(id, planeState(fact.state()), handoff));
        });

        LinkedHashMap<String, PullRequest> pullRequests = new LinkedHashMap<>();
        given.gitea().pullRequests().forEach((id, fact) -> pullRequests.put(id, new PullRequest(
                id,
                pullRequestState(fact.state()),
                Boolean.TRUE.equals(fact.mergeable()),
                Boolean.TRUE.equals(fact.conflicted()),
                fact.repository(),
                fact.head(),
                fact.base())));
        return new ContractWorld(implementations, pullRequests);
    }

    public List<Implementation> readImplementations() {
        record(EventKind.READ, "plane.list_implementations", "plane",
                implementations.size() + " implementation children");
        return List.copyOf(implementations.values());
    }

    public Optional<Implementation> readImplementation(String id) {
        Implementation implementation = implementations.get(id);
        record(EventKind.READ, "plane.get_implementation", id,
                implementation == null ? "not found" : describe(implementation));
        return Optional.ofNullable(implementation);
    }

    public Optional<PullRequest> readPullRequest(String id) {
        PullRequest pullRequest = pullRequests.get(id);
        record(EventKind.READ, "gitea.get_pull_request", id,
                pullRequest == null ? "not found" : describe(pullRequest));
        return Optional.ofNullable(pullRequest);
    }

    /** Models the external system's mechanics, not any routine's eligibility policy. */
    public MutationResult mergePullRequest(String id) {
        PullRequest pullRequest = pullRequests.get(id);
        record(EventKind.MUTATION_ATTEMPT, "gitea.merge_pull_request", id,
                pullRequest == null ? "pull request not found" : describe(pullRequest));
        if (pullRequest == null) {
            return noop("pull_request_not_found", id, "No pull request exists");
        }
        if (pullRequest.state() == PullRequestState.MERGED) {
            return noop("merge_already_merged_pull_request", id, "Pull request is already merged");
        }
        if (pullRequest.state() != PullRequestState.OPEN) {
            return noop("merge_non_open_pull_request", id, "Pull request state is " + pullRequest.state());
        }
        if (!pullRequest.mergeable() || pullRequest.conflicted()) {
            return noop("merge_unmergeable_pull_request", id, "Pull request is not mergeable");
        }

        PullRequest merged = new PullRequest(
                pullRequest.id(), PullRequestState.MERGED, pullRequest.mergeable(), pullRequest.conflicted(),
                pullRequest.repository(), pullRequest.head(), pullRequest.base());
        pullRequests.put(id, merged);
        record(EventKind.MUTATION_SUCCEEDED, "pull_request_merged", id, "OPEN -> MERGED");
        return new MutationResult(true, "pull_request_merged", "Merged " + id);
    }

    public void record(EventKind kind, String action, String subject, String detail) {
        events.add(new Event(events.size() + 1, kind, action, subject, detail));
    }

    public List<Event> events() {
        return List.copyOf(events);
    }

    public Map<String, PullRequestState> pullRequestStates() {
        LinkedHashMap<String, PullRequestState> states = new LinkedHashMap<>();
        pullRequests.forEach((id, pullRequest) -> states.put(id, pullRequest.state()));
        return Collections.unmodifiableMap(states);
    }

    public String describeFinalState() {
        StringBuilder description = new StringBuilder("plane:\n");
        implementations.forEach((id, implementation) -> description
                .append("  ").append(id).append(": ").append(describe(implementation)).append('\n'));
        description.append("gitea:\n");
        pullRequests.forEach((id, pullRequest) -> description
                .append("  ").append(id).append(": ").append(describe(pullRequest)).append('\n'));
        return description.toString().stripTrailing();
    }

    public String describeAudit() {
        return events.stream().map(Event::toString).reduce((left, right) -> left + "\n" + right).orElse("(none)");
    }

    private MutationResult noop(String outcome, String id, String detail) {
        record(EventKind.NOOP, outcome, id, detail);
        return new MutationResult(false, outcome, detail);
    }

    private static String describe(Implementation implementation) {
        Handoff handoff = implementation.handoff();
        String handoffDescription = handoff == null
                ? "no handoff"
                : "%s current=%s pullRequest=%s repository=%s reviewedHead=%s reviewedBase=%s reviewedAt=%s "
                        .formatted(handoff.type(), handoff.current(), handoff.pullRequestId(), handoff.repository(),
                                handoff.reviewedHead(), handoff.reviewedBase(), handoff.reviewedAt())
                        + "validation=%s acceptanceReference=%s".formatted(
                                handoff.validation(), handoff.acceptanceReference());
        return "state=%s, handoff={%s}".formatted(implementation.state(), handoffDescription);
    }

    private static String describe(PullRequest pullRequest) {
        return "state=%s, mergeable=%s, conflicted=%s, repository=%s, head=%s, base=%s".formatted(
                pullRequest.state(), pullRequest.mergeable(), pullRequest.conflicted(), pullRequest.repository(),
                pullRequest.head(), pullRequest.base());
    }

    private static PlaneState planeState(String value) {
        return PlaneState.valueOf(enumName(value));
    }

    private static PullRequestState pullRequestState(String value) {
        return PullRequestState.valueOf(enumName(value));
    }

    private static String enumName(String value) {
        return normalized(value).toUpperCase(Locale.ROOT);
    }

    private static String normalized(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replace('-', '_').replace(' ', '_').toLowerCase(Locale.ROOT);
    }
}
