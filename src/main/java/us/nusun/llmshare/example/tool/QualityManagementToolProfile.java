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
                        "manage_work_item_assignee",
                        "manage_work_item_label",
                        "list_work_item_activities",
                        "list_work_item_comments",
                        "retrieve_work_item_comment",
                        "create_work_item_comment",
                        "update_work_item_comment",
                        "list_labels",
                        "create_label",
                        "list_states",
                        "list_work_item_types",
                        "retrieve_work_item_type",
                        "resolve_work_item_type")
                .promptGuidance("""
                        - QA owns test engineering and behavioral evidence, not product acceptance
                        - Product Owner owns Epic lifecycle and final acceptance; never move an Epic to Done
                        - derive QA work from explicit Epic acceptance intent
                        - QA test engineering is a Task child of the Epic carrying work:test; resolve Task once and
                          ensure work:test exists before creating missing QA work
                        - QA owns work:test lifecycle: create/assign to self, claim Todo -> In Progress, and move Done
                          only after any required test-code PR is merged and durable test work plus required execution
                          evidence are complete
                        - keep QA WIP low: normally one work:test Task In Progress and at most one executable Todo queued
                          ahead; additional foreseeable coverage remains Backlog until capacity opens
                        - prefer executing assigned actionable test work over creating additional Todo test work
                        - product behavior failures are Bug work items carrying work:defect; resolve Bug once and ensure
                          work:defect exists before recording a new defect
                        - create a new defect unparented in Backlog; include reproducible evidence plus the
                          observed/candidate Epic context in its description/comment, but do not classify it into an Epic yourself
                        - PO owns defect classification into the correct Epic and product priority; PM then assigns repair
                          to DEV or ARCH
                        - a repair Bug stays In Progress after its fix PR merges until QA retests
                        - QA closes work:defect Done only after current evidence proves the failed behavior is fixed
                        - product ambiguity is not a defect; return it to PO
                        - a broken or invalid test is QA work, not a product defect
                        - an architecture/testability issue should be reported with evidence and routed without
                          rewriting another role's work
                        - lifecycle uses only Backlog, Todo, In Progress, Done, Cancelled

                        SKILL: typed-work-navigation
                        - use exact type ids and labels; never infer Task/Bug/Epic from title wording
                        - retrieve a selected item when parent or full acceptance/evidence context is required
                        """)
                .defaultArgument(PlaneMcpServer.ID, "list_projects", "fields", "id,name,identifier")
                .defaultArgument(
                        PlaneMcpServer.ID,
                        "list_work_items",
                        "fields",
                        "id,name,sequence_id,state,priority,assignees,labels,type_id")
                .defaultArgument(PlaneMcpServer.ID, "list_work_items", "per_page", 12)
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
