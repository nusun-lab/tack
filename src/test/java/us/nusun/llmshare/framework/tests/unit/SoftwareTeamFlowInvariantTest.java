package us.nusun.llmshare.framework.tests.unit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class SoftwareTeamFlowInvariantTest {
    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path));
    }

    @Test
    void architectPreservesIntegrationContextAndCanResolveCanonicalRepoOwner() throws IOException {
        String routine = read("src/main/java/us/nusun/llmshare/example/routine/ArchitectRoutine.java");
        String profile = read("src/main/java/us/nusun/llmshare/example/tool/IntegrationToolProfile.java");

        assertTrue(routine.contains("thought-enter?name=architect-integration-focus"));
        assertTrue(routine.contains("architectIntegrationIterations"));
        assertTrue(routine.contains("architect-integration-batch"));
        assertTrue(routine.contains("Include DEV-, QA-, and Architect-authored PRs"));
        assertTrue(routine.contains("never let an unsafe"));
        assertTrue(profile.contains("\"search_repos\""));
        assertTrue(profile.contains("\"list_pull_requests\""));
        assertTrue(profile.contains("integration is batch work"));
        assertTrue(profile.contains("agent-workspace"));
    }

    @Test
    void pmRetainsFocusedProductiveLoopsInsteadOfOneShotObservation() throws IOException {
        String routine = read("src/main/java/us/nusun/llmshare/example/routine/ProjectManagerRoutine.java");

        assertTrue(routine.contains("thought-enter?name=pm-active-flow-focus"));
        assertTrue(routine.contains("pmActiveFlowIterations"));
        assertTrue(routine.contains("thought-enter?name=pm-planning-focus"));
        assertTrue(routine.contains("pmPlanningIterations"));
        assertTrue(routine.contains("act.pm-flow.durableProgress"));
        assertTrue(routine.contains("act.pm-planning.durableProgress"));
    }

    @Test
    void poAndQaKeepFocusedWorkMemoryAcrossMultiStepActions() throws IOException {
        String po = read("src/main/java/us/nusun/llmshare/example/routine/ProductOwnerRoutine.java");
        String qa = read("src/main/java/us/nusun/llmshare/example/routine/QualityEngineerRoutine.java");

        assertTrue(po.contains("thought-enter?name=po-epic-work-focus"));
        assertTrue(po.contains("poEpicWorkIterations"));
        assertTrue(po.contains("thought-enter?name=po-acceptance-focus"));
        assertTrue(qa.contains("thought-enter?name=qa-test-engineering-focus"));
        assertTrue(qa.contains("thought-enter?name=qa-defect-retest-focus"));
    }

    @Test
    void planeClassificationAndOwnershipAreMutationPreconditions() throws IOException {
        String delivery = read("src/main/java/us/nusun/llmshare/example/tool/DeliveryManagementToolProfile.java");
        String engineering = read("src/main/java/us/nusun/llmshare/example/tool/EngineeringWorkToolProfile.java");
        String product = read("src/main/java/us/nusun/llmshare/example/tool/ProductOwnershipToolProfile.java");
        String quality = read("src/main/java/us/nusun/llmshare/example/tool/QualityManagementToolProfile.java");
        String readonly = read("src/main/java/us/nusun/llmshare/example/tool/WorkspaceReadonlyToolProfile.java");

        assertTrue(delivery.contains("unknown classification is a stop condition"));
        assertTrue(engineering.contains("never claim, reassign, or lifecycle-mutate work with unknown classification"));
        assertTrue(product.contains("every type:epic outside Backlog must be assigned to the active Product Owner"));
        assertTrue(quality.contains("every type:task + work:test outside Backlog must remain assigned to QA"));
        assertTrue(readonly.contains("every Todo, In Progress, Done, or Cancelled item must have an owner"));
    }

    @Test
    void chatCannotBecomeAWorkflowGateAndDirectorStaysOutOfRoleLocalQueues() throws IOException {
        String chat = read("src/main/java/us/nusun/llmshare/example/tool/TeamChatToolProfile.java");
        String director = read("src/main/java/us/nusun/llmshare/example/routine/DirectorRoutine.java");

        assertTrue(chat.contains("\"green\", \"approved\", \"review ready\", \"integration ready\""));
        assertTrue(chat.contains("never authorize or block Plane lifecycle or Gitea integration"));
        assertTrue(director.contains("Do not audit normal Story/Test queue depth"));
        assertTrue(director.contains("Do not invent a mission-level blocker"));
    }

    @Test
    void flowBuffersDoNotSerializeUsefulWorkAndPlaneUsesSparseDiscovery() throws IOException {
        String developer = read("src/main/java/us/nusun/llmshare/example/routine/ApplicationDeveloperRoutine.java");
        String delivery = read("src/main/java/us/nusun/llmshare/example/tool/DeliveryManagementToolProfile.java");
        String engineering = read("src/main/java/us/nusun/llmshare/example/tool/EngineeringWorkToolProfile.java");
        String product = read("src/main/java/us/nusun/llmshare/example/tool/ProductOwnershipToolProfile.java");
        String quality = read("src/main/java/us/nusun/llmshare/example/tool/QualityManagementToolProfile.java");

        assertTrue(developer.contains("roughly 2-3 actively coded source units"));
        assertFalse(developer.contains("Do not start a second source unit while one task PR remains open/unmerged"));
        assertTrue(delivery.contains("2-3 assigned executable Todo units"));
        assertTrue(product.contains("maintain a deeper Todo runway while normally allowing no more than three Epics In Progress"));
        assertTrue(quality.contains("2-3 work:test Tasks In Progress"));
        assertTrue(engineering.contains("id,name,sequence_id,state,priority,assignees"));
        assertFalse(delivery.contains("\"manage_work_item_assignee\""));
        assertFalse(product.contains("\"manage_work_item_assignee\""));
        assertFalse(quality.contains("\"manage_work_item_assignee\""));
    }

    @Test
    void epicReadinessAndDeliveryCapacityRemainDistinct() throws IOException {
        String owen = read("src/main/java/us/nusun/llmshare/example/persona/OwenPersona.java");
        String pm = read("src/main/java/us/nusun/llmshare/example/routine/ProjectManagerRoutine.java");
        String po = read("src/main/java/us/nusun/llmshare/example/routine/ProductOwnerRoutine.java");
        String dev = read("src/main/java/us/nusun/llmshare/example/routine/ApplicationDeveloperRoutine.java");
        String arch = read("src/main/java/us/nusun/llmshare/example/routine/ArchitectRoutine.java");
        String delivery = read("src/main/java/us/nusun/llmshare/example/tool/DeliveryManagementToolProfile.java");
        String product = read("src/main/java/us/nusun/llmshare/example/tool/ProductOwnershipToolProfile.java");
        String readonly = read("src/main/java/us/nusun/llmshare/example/tool/WorkspaceReadonlyToolProfile.java");
        String director = read("src/main/java/us/nusun/llmshare/example/routine/DirectorRoutine.java");

        assertFalse(owen.contains("low WIP"));
        assertFalse(owen.contains("limiting simultaneous work"));
        assertFalse(pm.contains("one type:epic work item In Progress"));
        assertFalse(pm.contains("one dependency-satisfied Backlog Story candidate"));
        assertTrue(pm.contains("up to three"));
        assertTrue(pm.contains("Plan across all PO-owned type:epic items in In Progress or Todo"));
        assertTrue(pm.contains("Never move Todo, In Progress, Done, or Cancelled work backward"));
        assertTrue(po.contains("normally no more than three Epics In Progress"));
        assertTrue(product.contains("product readiness alone never activates an Epic"));
        assertTrue(delivery.contains("AgentRun presence controls who can execute now"));
        assertTrue(delivery.contains("Done and Cancelled are terminal"));
        assertTrue(readonly.contains("normal active Epic depth is at most three"));
        assertTrue(dev.contains("verify its parent Epic is In Progress before claiming it"));
        assertTrue(arch.contains("parent Epic is In Progress before claiming it"));
        assertFalse(director.contains("reciprocally integrating Architect-authored PRs"));
    }

    @Test
    void planeWorkItemQueriesUseExactSafeRecipes() throws IOException {
        String delivery = read("src/main/java/us/nusun/llmshare/example/tool/DeliveryManagementToolProfile.java");
        String engineering = read("src/main/java/us/nusun/llmshare/example/tool/EngineeringWorkToolProfile.java");
        String product = read("src/main/java/us/nusun/llmshare/example/tool/ProductOwnershipToolProfile.java");
        String quality = read("src/main/java/us/nusun/llmshare/example/tool/QualityManagementToolProfile.java");
        String readonly = read("src/main/java/us/nusun/llmshare/example/tool/WorkspaceReadonlyToolProfile.java");

        for (String profile : new String[]{delivery, engineering, product, quality, readonly}) {
            assertTrue(profile.contains("expand=\"labels\""));
            assertTrue(profile.contains("fields=\"id,name,sequence_id,state,labels\""));
            assertTrue(profile.contains("Never omit fields"));
            assertTrue(profile.contains("Never attempt to retrieve"));
        }
    }

    @Test
    void developerKeepsKnownGoodProductiveLoop() throws IOException {
        String developer = read("src/main/java/us/nusun/llmshare/example/routine/ApplicationDeveloperRoutine.java");

        assertTrue(developer.contains("dev-delivery-loop"));
        assertTrue(developer.contains("devProductiveIterations"));
        assertTrue(developer.contains("thought-enter?name=dev-implementation"));
        assertTrue(developer.contains(
                "choices=missing-basics,reconcile,integration,implementation,coordination,complete,wait"));
    }
}
