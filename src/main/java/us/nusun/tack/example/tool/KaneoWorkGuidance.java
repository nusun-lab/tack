package us.nusun.tack.example.tool;

final class KaneoWorkGuidance {
    private KaneoWorkGuidance() {}

    static final String PROJECT = """
            Resolve the Tack Software Team workspace and match the current mission anchor to the project name and slug.
            Reuse verified IDs. Recover an absent or rejected locator with list_workspaces or list_projects. Ambiguous
            matches require evidence before writes. Project inventory may show empty columns. Verify the board with
            list_project_columns or list_tasks and retain verified defaults.
            """;

    static final String TASKS = """
            Filter task lists by project, status slug and exact assignee as needed. Resolve your user with whoami
            and match teammates by configured email when member discovery is available. Keep IDs, slugs and tickets
            distinct. Use list labels for classification and follow task and related pages independently as needed.
            Fetch targeted detail for deferred descriptions or unverified context.
            """;

    static final String LIFECYCLE = """
            Use planned, to-do, in-progress and done. Archive abandoned work with a reason. Only done satisfies
            prerequisites and successful delivery. Use Gitea for review and dedicated status and assignment tools.
            Relations point from Epic to child (subtask) or prerequisite to dependent (blocks).
            """;

    static final String CLASSIFICATION = """
            Resume partial work by checking existing tasks before creating. Stage new work as planned while attaching
            its own classification label with create_label. Colors: epic #7c3aed, story #2563eb, test #059669,
            defect #dc2626. Each Story, Test and validated Defect has one Epic parent. Use update_task for content
            or priority. Keep evidence in comments.
            """;
}
