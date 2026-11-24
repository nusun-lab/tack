package us.nusun.tack.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.tack.framework.inventory.ToolProfileProvider;
import us.nusun.tack.framework.model.ToolProfile;

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
                        "project",
                        "member",
                        "workitem",
                        "workitem_activity",
                        "workitem_comment",
                        "label",
                        "state")
                .promptGuidance("""
                        - this profile updates already-created engineering work; the calling routine owns selection,
                          lifecycle policy, and completion criteria
                        - use only these Plane actions here: project=list/retrieve; member=me;
                          workitem=list/retrieve/retrieve_by_identifier/search/update; workitem_activity=list;
                          workitem_comment=list/retrieve/create/update; label=list; state=list
                        - before any lifecycle mutation, verify the selected item's attached labels and current assignees
                        - never infer classification or ownership from title, wording, comments, or surrounding context
                        - comments are evidence/context, not hidden lifecycle states
                        - do not create work items, labels, projects, test tasks, or defects in this profile

                        SKILL: plane-work-classification
                        - Tack work classification is represented by attached labels epic, story, test, or defect
                        - these labels are unrelated to Plane's native work-item type and type_id; never use type_id to
                          determine Tack classification

                        SKILL: plane-query-recipes
                        - Discovery: workitem(action="list", project_id=<verified>, per_page=12,
                          fields="id,name,sequence_id,state,priority,assignees")
                        - Classification: workitem(action="retrieve", project_id=<verified>,
                          workitem_id=<selected UUID>, expand="labels",
                          fields="id,name,sequence_id,state,labels")
                        - Description: workitem(action="retrieve", project_id=<verified>,
                          workitem_id=<selected UUID>, fields="id,name,sequence_id,state,description_html")
                        - Parent: workitem(action="retrieve", project_id=<verified>,
                          workitem_id=<selected UUID>, fields="id,name,sequence_id,state,parent"); never expand parent
                        - a field omitted by a sparse projection is unknown, never negative evidence
                        """)
                .hideArgument(PlaneMcpServer.ID, "workitem", "pql")
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
