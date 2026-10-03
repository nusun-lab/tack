package us.nusun.llmshare.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.framework.inventory.ToolProfileProvider;
import us.nusun.llmshare.framework.model.ToolProfile;

import java.util.List;

@ApplicationScoped
public class EngineeringWorkToolProfile implements ToolProfileProvider {
    public static final String ID = "engineering-work";

    private final PlaneMcpServer plane;
    private final int maxResultTokens;

    @Inject
    public EngineeringWorkToolProfile(
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
                        "update_work_item",
                        "list_work_item_activities",
                        "list_work_item_comments",
                        "retrieve_work_item_comment",
                        "create_work_item_comment",
                        "update_work_item_comment",
                        "list_labels",
                        "list_states")
                .promptGuidance("""
                        - this profile is for execution of already-created engineering work, not backlog design
                        - do not create work items, labels, projects, epics, test tasks, or defects
                        - type:story + work:implementation is implementation delivery under a type:epic; DEV or ARCH may
                          claim assigned Todo -> In Progress
                        - type:bug + work:defect is repair work associated with a type:epic after PO triage; DEV or ARCH
                          may claim it only after PM assigns it Todo
                        - never infer classification from title or wording; required labels must actually be attached
                        - never claim, reassign, or lifecycle-mutate work with unknown classification
                        - DEV/ARCH may claim only work already assigned to them; type:epic and type:task are never
                          engineer source work regardless of title, state, or surrounding context
                        - never change product priority or epic lifecycle
                        - work:implementation remains In Progress while its source PR is open; after verifying that PR merged,
                          the assigned engineer owns the story transition to Done
                        - an open PR waiting only on integration does not block claiming another independent assigned Todo
                          source unit; maintain roughly 2-3 actively coded units when enough useful work exists
                        - work:defect remains In Progress through fix merge; QA retests and closes the bug
                        - comments contain evidence/blockers only and never act as hidden workflow states
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
