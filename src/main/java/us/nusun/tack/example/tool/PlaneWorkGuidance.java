package us.nusun.tack.example.tool;

final class PlaneWorkGuidance {
    private PlaneWorkGuidance() {}

    static final String CLASSIFICATION = """
            SKILL: plane-work-classification
            - verify exactly one attached Tack classification label: epic, story, test, or defect
            - these labels represent work kind. Plane type_id and lifecycle state are separate fields
            """;

    static final String QUERIES = """
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
            """;
}
