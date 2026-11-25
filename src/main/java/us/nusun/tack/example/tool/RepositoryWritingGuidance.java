package us.nusun.tack.example.tool;

final class RepositoryWritingGuidance {
    private RepositoryWritingGuidance() {}

    static final String REPOSITORY_GUIDANCE = """
            - software-team mission repositories live under Gitea owner agent-workspace
            - use the persistent persona sandbox under /sandbox/repos and reuse an existing checkout, branch, or PR
            - when clone_url is unknown, use search_repos and the returned agent-workspace clone_url
            - canonical external Gitea is https://gitea.apps.cluster.lab.nusun.us
            - use pull_request_write for PR create/update work only and merge through the integration profile
            - include the exact Kaneo project and ticket in a PR for selected task work
            """;
}
