package us.nusun.tack.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import us.nusun.tack.framework.tool.ToolProfileProvider;
import us.nusun.tack.framework.tool.ToolProfile;

import java.util.List;

@ApplicationScoped
public class ProductOwnershipToolProfile implements ToolProfileProvider {
    public static final String ID = "product-ownership";

    private final PlaneMcpServer plane;

    @Inject
    public ProductOwnershipToolProfile(
            PlaneMcpServer plane) {
        this.plane = plane;
    }

    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(ToolProfile.builder(ID)
                .mcpServer(plane.definition())
                .allowTools(PlaneMcpServer.ID,
                        "project", "member", "workitem", "workitem_activity", "workitem_comment", "label", "state")
                .promptGuidance("""
                        - this profile creates and updates Plane product work; the calling routine owns product policy,
                          lifecycle decisions, acceptance criteria, prioritization decisions, and WIP policy
                        - use only these Plane actions here: project=list/retrieve; member=me;
                          workitem=list/retrieve/retrieve_by_identifier/search/create/update/manage_label;
                          workitem_activity=list; workitem_comment=list/retrieve/create/update; label=list/create; state=list
                        """ + PlaneWorkGuidance.CLASSIFICATION + """
                        - create a missing classification label only when the routine explicitly requires that kind, then
                          attach it explicitly with workitem(action="manage_label")
                        - before mutating an existing item based on kind, verify its attached labels
                        - use workitem(action="update", ...) with the complete assignees array for assignment changes
                        - comments preserve evidence/context and are not lifecycle states

                        """ + PlaneWorkGuidance.QUERIES)
                .build());
    }
}
