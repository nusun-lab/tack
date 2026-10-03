package us.nusun.llmshare.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.framework.inventory.ToolProfileProvider;
import us.nusun.llmshare.framework.model.ToolProfile;

import java.util.List;

@ApplicationScoped
public class QualityManagementToolProfile implements ToolProfileProvider {
    public static final String ID = "quality-management";

    private final PlaneMcpServer plane;
    private final int maxResultTokens;

    @Inject
    public QualityManagementToolProfile(
            PlaneMcpServer plane,
            @ConfigProperty(name = "agent.tool.profile.project-management.max-result-tokens", defaultValue = "5000")
            int maxResultTokens) {
        this.plane = plane;
        this.maxResultTokens = maxResultTokens;
    }

    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder(ID)
                .mcpServer(plane.definition())
                .allowTools(PlaneMcpServer.ID,
                        "get_me",
                        "list_projects",
                        "retrieve_project",
                        "list_work_items",
                        "retrieve_work_item",
                        "retrieve_work_item_by_identifier",
                        "search_work_items",
                        "create_work_item",
                        "update_work_item",
                        "manage_work_item_label",
                        "list_work_item_activities",
                        "list_work_item_comments",
                        "retrieve_work_item_comment",
                        "create_work_item_comment",
                        "update_work_item_comment",
                        "list_labels",
                        "create_label",
                        "list_states")
                .promptGuidance("""
                        - QA owns test engineering and behavioral evidence, not product acceptance
                        - Product Owner owns type:epic lifecycle and final acceptance; never move an epic to Done
                        - derive QA work directly from PO-authored Given/When/Then Epic acceptance
                        - if Gherkin is ambiguous or incomplete, return the exact product question to PO rather than
                          inventing acceptance behavior
                        - QA produces evidence and defects, not merge approvals; never create a required "green" phrase
                          or other chat-only integration gate
                        - QA test engineering is a child of the epic carrying type:task + work:test; ensure both labels
                          exist and attach both explicitly before treating work as QA test engineering
                        - QA owns work:test lifecycle: create/assign to self, claim Todo -> In Progress, and move Done
                          only after any required test-code PR is merged and durable test work plus required execution
                          evidence are complete
                        - every type:task + work:test outside Backlog must remain assigned to QA; verify classification and
                          ownership before lifecycle mutation, and stop rather than infer when labels are unavailable
                        - keep QA flow supplied: target roughly 2-3 work:test Tasks In Progress and 2-3 executable Todo
                          Tasks queued ahead when enough independent evidence work exists; Tasks waiting only on deployment,
                          integration, or another role do not consume active QA execution capacity
                        - treat 2-3 as a flow target, not a hard ceiling; prefer executing actionable evidence while keeping
                          the downstream queue replenished
                        - product behavior failures are work items carrying type:bug + work:defect; ensure both labels
                          exist and attach both explicitly when recording a new defect
                        - create a new defect unparented in Backlog; include reproducible evidence plus the
                          observed/candidate epic context in its description/comment, but do not parent it yourself
                        - PO owns defect association to the correct epic and product priority; PM then assigns repair
                          to DEV or ARCH
                        - a repair work:defect stays In Progress after its fix PR merges until QA retests
                        - QA closes work:defect Done only after current evidence proves the failed behavior is fixed
                        - product ambiguity is not a defect; return it to PO
                        - a broken or invalid test is QA work, not a product defect
                        - an architecture/testability issue should be reported with evidence and routed without
                          rewriting another role's work
                        - lifecycle uses only Backlog, Todo, In Progress, Done, Cancelled
                        - for assignment changes, use update_work_item with the complete assignees array; do not use
                          manage_work_item_assignee in this profile

                        SKILL: plane-query-recipes
                        - Discovery recipe: list_work_items(project_id=<verified>, fields="id,name,sequence_id,state,priority,assignees").
                          Use this sparse queue view for lifecycle, priority, and ownership. Do not add labels or parent.
                        - Classification recipe: retrieve_work_item(project_id=<verified>, work_item_id=<selected UUID>,
                          expand="labels", fields="id,name,sequence_id,state,labels"). Always include exactly these fields
                          for classification. Never omit fields and never include assignees in this call.
                        - Description recipe: retrieve_work_item(project_id=<verified>, work_item_id=<selected UUID>,
                          fields="id,name,sequence_id,state,description_html"). Do not include labels or assignees.
                        - Parent recipe: retrieve_work_item(project_id=<verified>, work_item_id=<selected UUID>,
                          fields="id,name,sequence_id,state,parent"). Never expand parent.
                        - Ownership and classification are deliberately separate calls. Never attempt to retrieve
                          assignees and expanded labels in the same WorkItemDetail response.
                        - If one exact recipe fails with a WorkItemDetail assignee/label/parent shape error, stop that
                          retrieval strategy for the pass. Never repeat an equivalent failing shape on another item.
                        - classification is authoritative only from attached labels; never infer task/bug/epic from title wording
                        - every classified work item carries exactly one type:* label
                        - retrieve a selected item when parent or full acceptance/evidence context is required
                        """)
                .defaultArgument(PlaneMcpServer.ID, "list_projects", "fields", "id,name,identifier")
                .defaultArgument(
                        PlaneMcpServer.ID,
                        "list_work_items",
                        "fields",
                        "id,name,sequence_id,state,priority,assignees")
                .defaultArgument(PlaneMcpServer.ID, "list_work_items", "per_page", 12)
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
