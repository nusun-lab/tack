package us.nusun.llmshare.framework.tests.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** External facts and mechanical operations shared by routine contract tests. */
public final class ContractWorld {
    public enum PlaneState {
        TODO,
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

    public record Workspace(String plane, String source, String documentation, String teamChat) {
    }

    public record DeliveryContext(
            String parentAcceptanceReference,
            String repository,
            String dependencyContext,
            String expectedReviewEvidence) {
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

    public record PlaneComment(String requiredOwner, String gap, String evidence) {
    }

    public record Implementation(
            String id,
            PlaneState state,
            String assignee,
            String activity,
            DeliveryContext deliveryContext,
            Handoff handoff,
            Map<String, PlaneComment> comments) {
        public Implementation {
            comments = Collections.unmodifiableMap(new LinkedHashMap<>(comments));
        }
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

    public record SourceRepository(
            String id,
            String revision,
            String defaultBranch,
            Map<String, String> branches,
            Map<String, String> files) {
        public SourceRepository {
            branches = Collections.unmodifiableMap(new LinkedHashMap<>(branches));
            files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
        }
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

    private final Workspace workspace;
    private final LinkedHashMap<String, Implementation> implementations;
    private final LinkedHashMap<String, PullRequest> pullRequests;
    private final LinkedHashMap<String, SourceRepository> sourceRepositories;
    private final List<Event> events = new ArrayList<>();

    private ContractWorld(
            Workspace workspace,
            LinkedHashMap<String, Implementation> implementations,
            LinkedHashMap<String, PullRequest> pullRequests,
            LinkedHashMap<String, SourceRepository> sourceRepositories) {
        this.workspace = workspace;
        this.implementations = implementations;
        this.pullRequests = pullRequests;
        this.sourceRepositories = sourceRepositories;
    }

    /** Materializes only declared facts; no routine eligibility policy is applied. */
    public static ContractWorld from(ContractScenario.Given given) {
        Workspace workspace = workspace(given.workspace());
        LinkedHashMap<String, Implementation> implementations = new LinkedHashMap<>();
        map(given.plane().implementations()).forEach((id, fact) -> {
            ContractScenario.Handoff sourceHandoff = fact.handoff();
            Handoff handoff = sourceHandoff == null ? null : new Handoff(
                    normalized(sourceHandoff.type()),
                    Boolean.TRUE.equals(sourceHandoff.current()),
                    sourceHandoff.pullRequest(),
                    sourceHandoff.repository(),
                    sourceHandoff.reviewedHead(),
                    sourceHandoff.reviewedBase(),
                    sourceHandoff.reviewedAt(),
                    sourceHandoff.validation(),
                    sourceHandoff.acceptanceReference());
            ContractScenario.DeliveryContext sourceContext = fact.deliveryContext();
            DeliveryContext deliveryContext = sourceContext == null ? null : new DeliveryContext(
                    sourceContext.parentAcceptanceReference(),
                    sourceContext.repository(),
                    sourceContext.dependencyContext(),
                    sourceContext.expectedReviewEvidence());
            LinkedHashMap<String, PlaneComment> comments = new LinkedHashMap<>();
            map(fact.comments()).forEach((type, comment) -> comments.put(normalized(type), new PlaneComment(
                    normalized(comment.requiredOwner()), normalized(comment.gap()), comment.evidence())));
            implementations.put(id, new Implementation(
                    id,
                    planeState(fact.state()),
                    normalized(fact.assignee()),
                    normalized(fact.activity()),
                    deliveryContext,
                    handoff,
                    comments));
        });

        LinkedHashMap<String, PullRequest> pullRequests = new LinkedHashMap<>();
        if (given.gitea() != null) {
            map(given.gitea().pullRequests()).forEach((id, fact) -> pullRequests.put(id, new PullRequest(
                    id,
                    pullRequestState(fact.state()),
                    Boolean.TRUE.equals(fact.mergeable()),
                    Boolean.TRUE.equals(fact.conflicted()),
                    fact.repository(),
                    fact.head(),
                    fact.base())));
        }

        LinkedHashMap<String, SourceRepository> sourceRepositories = new LinkedHashMap<>();
        if (given.source() != null) {
            map(given.source().repositories()).forEach((id, fact) -> sourceRepositories.put(id, new SourceRepository(
                    id,
                    fact.revision(),
                    fact.defaultBranch(),
                    map(fact.branches()),
                    map(fact.files()))));
        }
        return new ContractWorld(workspace, implementations, pullRequests, sourceRepositories);
    }

    public Workspace readWorkspace() {
        record(EventKind.READ, "workspace.read_basics", "workspace", describe(workspace));
        return workspace;
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

    public MutationResult addOrRefreshPlaneComment(String id, String type, PlaneComment comment) {
        String normalizedType = normalized(type);
        Implementation implementation = implementations.get(id);
        record(EventKind.MUTATION_ATTEMPT, "plane_comment_mutation_attempt", id,
                "type=%s requiredOwner=%s gap=%s".formatted(
                        normalizedType, comment.requiredOwner(), comment.gap()));
        if (implementation == null) {
            return noop("implementation_not_found", id, "Cannot add comment to a missing implementation");
        }
        PlaneComment existing = implementation.comments().get(normalizedType);
        if (comment.equals(existing)) {
            return noop("equivalent_plane_comment_exists", id, "Equivalent " + normalizedType + " comment exists");
        }

        LinkedHashMap<String, PlaneComment> comments = new LinkedHashMap<>(implementation.comments());
        comments.put(normalizedType, comment);
        implementations.put(id, new Implementation(
                implementation.id(), implementation.state(), implementation.assignee(), implementation.activity(),
                implementation.deliveryContext(), implementation.handoff(), comments));
        record(EventKind.MUTATION_SUCCEEDED, "plane_comment_recorded", id,
                "type=%s requiredOwner=%s gap=%s evidence=%s".formatted(
                        normalizedType, comment.requiredOwner(), comment.gap(), comment.evidence()));
        return new MutationResult(true, "plane_comment_recorded", "Recorded " + normalizedType + " comment on " + id);
    }

    public Optional<PullRequest> readPullRequest(String id) {
        PullRequest pullRequest = pullRequests.get(id);
        record(EventKind.READ, "gitea.get_pull_request", id,
                pullRequest == null ? "not found" : describe(pullRequest));
        return Optional.ofNullable(pullRequest);
    }

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

    public Optional<SourceRepository> readSourceRepository(String id) {
        SourceRepository repository = sourceRepositories.get(id);
        record(EventKind.READ, "source_repository_read", id,
                repository == null ? "not found" : describe(repository));
        return Optional.ofNullable(repository);
    }

    /** A deliberately conspicuous mechanical mutation for source-safety contracts. */
    public MutationResult mutateSource(String id, String operation) {
        SourceRepository repository = sourceRepositories.get(id);
        record(EventKind.MUTATION_ATTEMPT, "source_mutation_attempt", id, "operation=" + operation);
        if (repository == null) {
            return noop("source_repository_not_found", id, "No source repository exists");
        }
        LinkedHashMap<String, String> branches = new LinkedHashMap<>(repository.branches());
        String branch = repository.defaultBranch();
        branches.put(branch, branches.getOrDefault(branch, repository.revision()) + "+" + operation);
        LinkedHashMap<String, String> files = new LinkedHashMap<>(repository.files());
        files.put(".contract-mutation-marker", operation);
        SourceRepository mutated = new SourceRepository(
                id, repository.revision() + "+" + operation, branch, branches, files);
        sourceRepositories.put(id, mutated);
        record(EventKind.MUTATION_SUCCEEDED, "source_mutated", id, "operation=" + operation);
        return new MutationResult(true, "source_mutated", "Mutated source repository " + id);
    }

    public void record(EventKind kind, String action, String subject, String detail) {
        events.add(new Event(events.size() + 1, kind, action, subject, detail));
    }

    public List<Event> events() {
        return List.copyOf(events);
    }

    public Map<String, Object> snapshot() {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("workspace", workspaceSnapshot());
        root.put("plane", Map.of("implementations", implementationSnapshot()));
        root.put("gitea", Map.of("pullRequests", pullRequestSnapshot()));
        root.put("source", Map.of("repositories", sourceSnapshot()));
        return Collections.unmodifiableMap(root);
    }

    public String describeFinalState() {
        StringBuilder output = new StringBuilder();
        appendMap(output, snapshot(), 0);
        return output.toString().stripTrailing();
    }

    public String describeAudit() {
        return events.stream().map(Event::toString).reduce((left, right) -> left + "\n" + right).orElse("(none)");
    }

    private Map<String, Object> workspaceSnapshot() {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        if (workspace != null) {
            put(result, "plane", workspace.plane());
            put(result, "source", workspace.source());
            put(result, "documentation", workspace.documentation());
            put(result, "teamChat", workspace.teamChat());
        }
        return result;
    }

    private Map<String, Object> implementationSnapshot() {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        implementations.forEach((id, implementation) -> {
            LinkedHashMap<String, Object> fact = new LinkedHashMap<>();
            fact.put("state", normalized(implementation.state().name()));
            put(fact, "assignee", implementation.assignee());
            put(fact, "activity", implementation.activity());
            if (implementation.deliveryContext() != null) {
                LinkedHashMap<String, Object> context = new LinkedHashMap<>();
                put(context, "parentAcceptanceReference", implementation.deliveryContext().parentAcceptanceReference());
                put(context, "repository", implementation.deliveryContext().repository());
                put(context, "dependencyContext", implementation.deliveryContext().dependencyContext());
                put(context, "expectedReviewEvidence", implementation.deliveryContext().expectedReviewEvidence());
                fact.put("deliveryContext", context);
            }
            if (implementation.handoff() != null) {
                LinkedHashMap<String, Object> handoff = new LinkedHashMap<>();
                handoff.put("type", implementation.handoff().type());
                handoff.put("current", implementation.handoff().current());
                put(handoff, "pullRequest", implementation.handoff().pullRequestId());
                put(handoff, "repository", implementation.handoff().repository());
                put(handoff, "reviewedHead", implementation.handoff().reviewedHead());
                put(handoff, "reviewedBase", implementation.handoff().reviewedBase());
                put(handoff, "reviewedAt", implementation.handoff().reviewedAt());
                put(handoff, "validation", implementation.handoff().validation());
                put(handoff, "acceptanceReference", implementation.handoff().acceptanceReference());
                fact.put("handoff", handoff);
            }
            LinkedHashMap<String, Object> comments = new LinkedHashMap<>();
            implementation.comments().forEach((type, comment) -> {
                LinkedHashMap<String, Object> commentFact = new LinkedHashMap<>();
                put(commentFact, "requiredOwner", comment.requiredOwner());
                put(commentFact, "gap", comment.gap());
                put(commentFact, "evidence", comment.evidence());
                comments.put(type, commentFact);
            });
            fact.put("comments", comments);
            result.put(id, fact);
        });
        return result;
    }

    private Map<String, Object> pullRequestSnapshot() {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        pullRequests.forEach((id, pullRequest) -> {
            LinkedHashMap<String, Object> fact = new LinkedHashMap<>();
            fact.put("state", normalized(pullRequest.state().name()));
            fact.put("mergeable", pullRequest.mergeable());
            fact.put("conflicted", pullRequest.conflicted());
            put(fact, "repository", pullRequest.repository());
            put(fact, "head", pullRequest.head());
            put(fact, "base", pullRequest.base());
            result.put(id, fact);
        });
        return result;
    }

    private Map<String, Object> sourceSnapshot() {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        sourceRepositories.forEach((id, repository) -> {
            LinkedHashMap<String, Object> fact = new LinkedHashMap<>();
            put(fact, "revision", repository.revision());
            put(fact, "defaultBranch", repository.defaultBranch());
            fact.put("branches", new LinkedHashMap<>(repository.branches()));
            fact.put("files", new LinkedHashMap<>(repository.files()));
            result.put(id, fact);
        });
        return result;
    }

    private MutationResult noop(String outcome, String id, String detail) {
        record(EventKind.NOOP, outcome, id, detail);
        return new MutationResult(false, outcome, detail);
    }

    private static Workspace workspace(ContractScenario.Workspace fact) {
        if (fact == null) {
            return null;
        }
        return new Workspace(
                normalized(fact.plane()), normalized(fact.source()),
                normalized(fact.documentation()), normalized(fact.teamChat()));
    }

    private static String describe(Workspace workspace) {
        return workspace == null ? "not declared" : "plane=%s source=%s documentation=%s teamChat=%s".formatted(
                workspace.plane(), workspace.source(), workspace.documentation(), workspace.teamChat());
    }

    private static String describe(Implementation implementation) {
        return "state=%s assignee=%s activity=%s deliveryContext=%s handoff=%s comments=%s".formatted(
                implementation.state(), implementation.assignee(), implementation.activity(),
                implementation.deliveryContext(), implementation.handoff(), implementation.comments());
    }

    private static String describe(PullRequest pullRequest) {
        return "state=%s mergeable=%s conflicted=%s repository=%s head=%s base=%s".formatted(
                pullRequest.state(), pullRequest.mergeable(), pullRequest.conflicted(), pullRequest.repository(),
                pullRequest.head(), pullRequest.base());
    }

    private static String describe(SourceRepository repository) {
        return "revision=%s defaultBranch=%s branches=%s files=%s".formatted(
                repository.revision(), repository.defaultBranch(), repository.branches(), repository.files().keySet());
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

    private static <K, V> LinkedHashMap<K, V> map(Map<K, V> values) {
        return values == null ? new LinkedHashMap<>() : new LinkedHashMap<>(values);
    }

    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null && (!(value instanceof String text) || !text.isBlank())) {
            target.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static void appendMap(StringBuilder output, Map<String, ?> values, int indent) {
        values.forEach((key, value) -> {
            output.append("  ".repeat(indent)).append(key).append(':');
            if (value instanceof Map<?, ?> nested) {
                output.append('\n');
                appendMap(output, (Map<String, ?>) nested, indent + 1);
            } else {
                output.append(' ').append(value).append('\n');
            }
        });
    }
}
