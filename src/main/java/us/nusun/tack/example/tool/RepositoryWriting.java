package us.nusun.tack.example.tool;

import us.nusun.tack.framework.model.ToolProfile;

final class RepositoryWriting {
    private RepositoryWriting() {}

    static final String REPOSITORY_GUIDANCE = """
            - software-team mission repositories live under Gitea owner agent-workspace
            - use the persistent persona sandbox under /sandbox/repos and reuse an existing checkout, branch, or PR
            - when clone_url is unknown, use search_repos and the returned agent-workspace clone_url
            - canonical external Gitea is https://gitea.apps.cluster.lab.nusun.us
            - use pull_request_write for PR create/update work only and merge through the integration profile
            """;

    static ToolProfile.Builder profile(String id, GiteaMcpServer gitea) {
        return ToolProfile.builder(id)
                .mcpServer(gitea.definition())
                .allowTool("sandbox", "sandbox_exec")
                .allowTools(GiteaMcpServer.ID,
                        "get_me",
                        "search_repos",
                        "get_repository_tree",
                        "get_dir_contents",
                        "get_file_contents",
                        "list_branches",
                        "list_commits",
                        "get_commit",
                        "list_pull_requests",
                        "pull_request_read",
                        "pull_request_write");
    }
}
