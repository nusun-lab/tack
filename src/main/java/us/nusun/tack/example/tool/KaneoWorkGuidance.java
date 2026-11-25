package us.nusun.tack.example.tool;

final class KaneoWorkGuidance {
    private KaneoWorkGuidance() {
    }

    static final String DISCOVERY = """
            - Use the verified current-mission project in the Tack Software Team workspace. Start with list_tasks
              scoped to that project, filtering by status and exact assignee ID where applicable. Check columns,
              plannedTasks, or archivedTasks as needed. Resolve people by whoami or exact configured email in
              list_workspace_members. Copy IDs verbatim, including case and repeated characters. If Kaneo reports
              a locator missing, compare the argument with the original response before drawing a conclusion.
            - Classify by the exact attached label name: epic, story, test, or defect. Task details can omit labels
              and list descriptions can be deferred. Use list evidence for classification, including when resolving
              a PR-linked ticket. Follow task and related-data pagination independently as far as needed. Missing
              evidence on a partial page is unknown. Fetch details, comments, or relations only for the decision at hand.
            """;

    static final String LIFECYCLE = """
            - Use planned for future work, to-do for ready work, in-progress for active delivery, and done for success.
              Reserve archived for explicitly abandoned work with a reason in a comment. An archived prerequisite
              cannot satisfy a dependency or acceptance gate. Leave in-review unused and use Gitea for PR state.
            - A blocks relation runs from prerequisite to dependent. Verify incoming prerequisites are done.
              A subtask relation runs from Epic to child. Verify relation direction directly.
            - Use dedicated status and assignment tools when available. Before retrying an uncertain write, read back
              the target and resume any incomplete work.
            """;

    static final String CLASSIFICATION = """
            - Create new work as planned while setting up its classification, required relations, and ownership.
              Reuse an exact matching task after an interrupted write. Create its own task label with create_label,
              workspaceId and taskId. Use epic #7c3aed, story #2563eb, test #059669, defect #dc2626. Reusing another
              task's label record can transfer it. Each Story, Test, and validated Defect has one Epic parent.
            - Use update_task for content or priority only. It reads and replaces the full task, so keep edits focused.
              Descriptions hold requirements and intent. Comments hold evidence and handoffs.
            """;
}
