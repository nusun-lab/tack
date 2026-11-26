package us.nusun.tack.example.tool;

final class RepositoryWritingGuidance {
    private RepositoryWritingGuidance() {}

    static final String DISCOVERY = """
            Reuse verified Gitea owner/repo pairs. To recover, search by mission anchor and confirm the exact
            repository and owner. For software-team missions the owner is agent-workspace.
            """;

    static final String REPOSITORY_GUIDANCE = DISCOVERY + """
            Use the returned clone_url and reuse the checkout under /sandbox/repos, task branch and PR.
            Validate in the sandbox. Link the exact Kaneo project and ticket in task PRs. Merge through integration.
            """;
}
