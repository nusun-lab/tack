package us.nusun.llmshare.example.routine;

import us.nusun.llmshare.framework.model.LlmJobRequest;
import us.nusun.llmshare.framework.tests.support.ContractPrimitiveBehavior;
import us.nusun.llmshare.framework.tests.support.ContractWorld;

import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic interpretation of Developer primitives for invalid delivery context. */
final class ApplicationDeveloperInvalidContextPrimitiveBehavior implements ContractPrimitiveBehavior {
    private static final String DEVELOPER = "application_developer";

    private final ContractWorld world;
    private String selectedImplementationId;
    private String missingContextGap;
    private boolean projectBasicsUsable;
    private boolean blockedRecorded;
    private ContractWorld.MutationResult lastAct = new ContractWorld.MutationResult(
            false, "not_started", "No act has run");

    ApplicationDeveloperInvalidContextPrimitiveBehavior(ContractWorld world) {
        this.world = world;
    }

    @Override
    public String toolResponse(LlmJobRequest request) {
        String primitiveId = primitiveId(request);
        return switch (primitiveId) {
            case "dev-workspace-basics-read" -> readWorkspaceBasics();
            case "dev-team-chat-sync" -> noMutation(
                    "team_chat_synced", "No relevant Developer conversation requires a reply");
            case "dev-work-selection" -> selectWork();
            case "dev-task-context-read" -> readTaskContext();
            case "dev-invalid-context-blocked" -> recordInvalidContextBlocker();
            case "dev-documentation-closeout" -> noDocumentationMutation();
            case "dev-team-chat-closeout" -> noMutation(
                    "team_chat_closeout_noop", "The durable Plane blocker already contains the required evidence");
            case "dev-no-work-note" -> noMutation(
                    "no_work_note_noop", "No additional chat note is useful after the recorded blocker");
            case "dev-source-context-plan" -> readSourceContext();
            case "dev-delivery-step" -> mutateSourceUnexpectedly();
            case "dev-validation-step" -> readSourceContext();
            case "dev-plane-status-evidence-update" -> noMutation(
                    "plane_status_update_noop", "No valid source delivery exists to report");
            case "dev-missing-basics-closeout" -> noMutation(
                    "missing_basics_closeout_noop", "No setup message required");
            default -> throw new AssertionError("Unexpected Application Developer tool primitive: " + primitiveId);
        };
    }

    @Override
    public Map<String, ?> synthesisResponse(LlmJobRequest request) {
        String primitive = primitive(request);
        String primitiveId = primitiveId(request);
        if ("decide".equals(primitive)) {
            return decision(primitiveId);
        }
        if (!"act".equals(primitive)) {
            throw new AssertionError("Unexpected Application Developer synthesis primitive: " + primitive);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", lastAct.detail());
        result.put("summary", lastAct.detail());
        result.put("outcome", lastAct.durableProgress() ? "SUCCEEDED" : "NOOP");
        result.put("durableProgress", lastAct.durableProgress());
        result.put("confidence", 1.0);
        return result;
    }

    private Map<String, ?> decision(String primitiveId) {
        boolean result;
        String reason;
        switch (primitiveId) {
            case "developer-has-project-basics" -> {
                result = projectBasicsUsable;
                reason = result ? "All declared shared project basics are available" : "A shared project basic is unavailable";
            }
            case "developer-has-work" -> {
                result = selectedImplementationId != null;
                reason = result
                        ? "Selected assigned active implementation " + selectedImplementationId
                        : "No assigned actionable implementation remains";
            }
            case "developer-has-valid-task-context" -> {
                result = selectedImplementationId != null && missingContextGap == null;
                reason = result ? "All required delivery context is present" : "Invalid context: " + missingContextGap;
            }
            case "developer-implementation-complete" -> {
                result = blockedRecorded;
                reason = result
                        ? "The implementation is durably blocked for Project Management"
                        : "No REVIEW READY or durable BLOCKED evidence exists";
            }
            default -> throw new AssertionError("Unexpected Application Developer decision primitive: " + primitiveId);
        }
        world.record(ContractWorld.EventKind.DECISION, primitiveId, selectedImplementationId,
                "result=" + result + " reason=" + reason);
        return Map.of(
                "decisionId", primitiveId,
                "result", result,
                "confidence", 1.0,
                "reason", reason);
    }

    private String readWorkspaceBasics() {
        ContractWorld.Workspace workspace = world.readWorkspace();
        projectBasicsUsable = workspace != null
                && "available".equals(workspace.plane())
                && "available".equals(workspace.source())
                && "available".equals(workspace.documentation())
                && "available".equals(workspace.teamChat());
        return noMutation("workspace_basics_read", projectBasicsUsable
                ? "Plane, source, documentation, and team chat are available"
                : "One or more shared project basics are unavailable");
    }

    private String selectWork() {
        selectedImplementationId = world.readImplementations().stream()
                .filter(implementation -> implementation.state() == ContractWorld.PlaneState.IN_PROGRESS)
                .filter(implementation -> DEVELOPER.equals(implementation.assignee()))
                .filter(implementation -> "active".equals(implementation.activity()))
                .filter(implementation -> !implementation.comments().containsKey("blocked"))
                .map(ContractWorld.Implementation::id)
                .findFirst()
                .orElse(null);
        missingContextGap = null;
        world.record(ContractWorld.EventKind.DECISION, "developer.select_implementation",
                selectedImplementationId, selectedImplementationId == null ? "none selected" : "selected active assigned work");
        return noMutation("work_selection_read", selectedImplementationId == null
                ? "No assigned actionable implementation remains"
                : "Selected " + selectedImplementationId);
    }

    private String readTaskContext() {
        ContractWorld.Implementation implementation = world.readImplementation(selectedImplementationId).orElseThrow();
        missingContextGap = invalidContextGap(implementation);
        String detail = missingContextGap == null
                ? "Required delivery context is complete"
                : "Required PM-owned delivery context is invalid: " + missingContextGap;
        world.record(ContractWorld.EventKind.DECISION, "developer.validate_delivery_context",
                implementation.id(), detail);
        return noMutation("task_context_read", detail);
    }

    private String recordInvalidContextBlocker() {
        if (selectedImplementationId == null || missingContextGap == null) {
            return noMutation("invalid_context_block_noop", "No invalid selected context exists to block");
        }
        ContractWorld.MutationResult mutation = world.addOrRefreshPlaneComment(
                selectedImplementationId,
                "blocked",
                new ContractWorld.PlaneComment(
                        "project_management",
                        missingContextGap,
                        "PM delivery context for " + selectedImplementationId + " is missing parent acceptance reference"));
        blockedRecorded = mutation.durableProgress() || "equivalent_plane_comment_exists".equals(mutation.outcome());
        lastAct = mutation;
        return mutation.detail();
    }

    private String noDocumentationMutation() {
        world.record(ContractWorld.EventKind.DECISION, "developer.documentation_change_needed",
                selectedImplementationId, "result=false because invalid task context produced no engineering knowledge change");
        return noMutation("documentation_closeout_noop", "No source documentation mutation is justified");
    }

    private String readSourceContext() {
        String repository = selectedRepository();
        world.readSourceRepository(repository);
        return noMutation("source_context_read", "Read source repository " + repository);
    }

    private String mutateSourceUnexpectedly() {
        ContractWorld.MutationResult mutation = world.mutateSource(selectedRepository(), "developer_delivery_step");
        lastAct = mutation;
        return mutation.detail();
    }

    private String selectedRepository() {
        return world.readImplementation(selectedImplementationId)
                .map(ContractWorld.Implementation::deliveryContext)
                .map(ContractWorld.DeliveryContext::repository)
                .orElse("unknown");
    }

    private String noMutation(String outcome, String detail) {
        lastAct = new ContractWorld.MutationResult(false, outcome, detail);
        return detail;
    }

    private static String invalidContextGap(ContractWorld.Implementation implementation) {
        ContractWorld.DeliveryContext context = implementation.deliveryContext();
        if (context == null || !text(context.parentAcceptanceReference())) {
            return "missing_parent_acceptance_reference";
        }
        if (!text(context.repository())) {
            return "missing_source_repository";
        }
        if (!text(context.dependencyContext())) {
            return "missing_dependency_context";
        }
        if (!text(context.expectedReviewEvidence())) {
            return "missing_expected_review_evidence";
        }
        return null;
    }

    private static boolean text(String value) {
        return value != null && !value.isBlank();
    }
}
