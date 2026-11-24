package us.nusun.tack.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.tack.framework.inventory.ToolProfileProvider;
import us.nusun.tack.framework.model.ToolProfile;

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
        return List.of(PlaneWork.profile(ID, plane)
                .promptGuidance("""
                        - this profile creates and updates Plane QA work and defect evidence; the calling routine owns QA
                          lifecycle, test-planning, defect, retest, and completion policy
                        - use only these Plane actions here: project=list/retrieve; member=me;
                          workitem=list/retrieve/retrieve_by_identifier/search/create/update/manage_label;
                          workitem_activity=list; workitem_comment=list/retrieve/create/update; label=list/create; state=list
                        """ + PlaneWork.CLASSIFICATION + """
                        - create a missing classification label only when the routine explicitly requires that kind, then
                          attach it explicitly with workitem(action="manage_label")
                        - before mutating an existing item based on kind, verify attached labels and current assignees
                        - use workitem(action="update", ...) with the complete assignees array for assignment changes
                        - comments preserve evidence/context and are not lifecycle states

                        """ + PlaneWork.QUERIES)
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
