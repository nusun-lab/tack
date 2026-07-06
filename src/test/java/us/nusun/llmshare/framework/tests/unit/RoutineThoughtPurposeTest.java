package us.nusun.llmshare.framework.tests.unit;

import org.junit.jupiter.api.Test;
import us.nusun.llmshare.example.routine.ApplicationDeveloperRoutine;
import us.nusun.llmshare.example.routine.ProductOwnerRoutine;
import us.nusun.llmshare.example.routine.ProjectManagerRoutine;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutineThoughtPurposeTest {
    @Test
    void projectManagerThoughtPurposesDescribeDurableAndPromotedMemory() {
        assertTrue(ProjectManagerRoutine.PROJECT_MANAGER_ROUTINE_THOUGHT.contains("Durable PM project anchors"));
        assertTrue(ProjectManagerRoutine.PM_BOOTSTRAP_THOUGHT.contains("Promote final URLs/blockers"));
        assertTrue(ProjectManagerRoutine.PM_CYCLE_THOUGHT.contains("selected product handoff"));
        assertTrue(ProjectManagerRoutine.PM_INTAKE_THOUGHT.contains("Promote selected handoff to pm-cycle"));
        assertTrue(ProjectManagerRoutine.PM_PLANNING_THOUGHT.contains("Promote created/updated task to pm-cycle"));
    }

    @Test
    void productOwnerThoughtPurposesDescribeDurableAndPromotedMemory() {
        assertTrue(ProductOwnerRoutine.PRODUCT_OWNER_ROUTINE_THOUGHT.contains("Durable product anchors"));
        assertTrue(ProductOwnerRoutine.PO_BASICS_THOUGHT.contains("Promote durable anchors"));
        assertTrue(ProductOwnerRoutine.PO_CYCLE_THOUGHT.contains("selected product item"));
        assertTrue(ProductOwnerRoutine.PO_BACKLOG_THOUGHT.contains("Promote selected item to po-cycle"));
        assertTrue(ProductOwnerRoutine.PO_ACCEPTANCE_THOUGHT.contains("Promote acceptance summary/page link to po-cycle"));
        assertTrue(ProductOwnerRoutine.PO_PRIORITY_THOUGHT.contains("requested PM action"));
    }

    @Test
    void applicationDeveloperThoughtPurposesDescribeDurableAndPromotedMemory() {
        assertTrue(ApplicationDeveloperRoutine.APPLICATION_DEVELOPER_ROUTINE_THOUGHT.contains("Durable developer anchors"));
        assertTrue(ApplicationDeveloperRoutine.DEV_BASICS_THOUGHT.contains("Promote durable anchors"));
        assertTrue(ApplicationDeveloperRoutine.DEV_CYCLE_THOUGHT.contains("selected assigned task"));
        assertTrue(ApplicationDeveloperRoutine.DEV_SELECTION_THOUGHT.contains("Promote selected task to dev-cycle"));
        assertTrue(ApplicationDeveloperRoutine.DEV_IMPLEMENTATION_THOUGHT.contains("Promote branch/commit/validation/blocker to dev-cycle"));
    }
}
