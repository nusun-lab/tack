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
        return List.of(PlaneWork.profile(ID, plane)
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

                        """ + PlaneWork.CLASSIFICATION + "\n" + PlaneWork.QUERIES)
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
