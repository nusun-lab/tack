package us.nusun.llmshare.framework.state;

import us.nusun.llmshare.framework.model.CreateResult;
import us.nusun.llmshare.framework.model.DecisionResult;
import us.nusun.llmshare.framework.model.StepResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AgentRun {
    private final String id;
    private final String missionId;
    private final String parentAgentRunId;
    private final String personaId;
    private final String routineId;
    private String status;
    private String currentPrimitive;
    private String currentId;
    private String latestObservation;
    private StepResult latestStepResult;
    private DecisionResult latestDecisionResult;
    private CreateResult latestCreateResult;
    private final List<StepResult> stepHistory = new ArrayList<>();
    private final Map<String, Object> workingState;
    private final Instant startedAt;

    public AgentRun(String id, String missionId, String personaId, String routineId, Map<String, Object> workingState) {
        this(id, missionId, null, personaId, routineId, workingState);
    }

    public AgentRun(String id, String missionId, String parentAgentRunId, String personaId, String routineId, Map<String, Object> workingState) {
        this.id = id;
        this.missionId = missionId;
        this.parentAgentRunId = parentAgentRunId;
        this.personaId = personaId;
        this.routineId = routineId;
        this.status = "active";
        this.workingState = workingState == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(workingState);
        this.startedAt = Instant.now();
    }

    public String id() { return id; }
    public String missionId() { return missionId; }
    public String parentAgentRunId() { return parentAgentRunId; }
    public String personaId() { return personaId; }
    public String routineId() { return routineId; }
    public String status() { return status; }
    public void status(String status) { this.status = status; }
    public String currentPrimitive() { return currentPrimitive; }
    public void currentPrimitive(String currentPrimitive) { this.currentPrimitive = currentPrimitive; }
    public String currentId() { return currentId; }
    public void currentId(String currentId) { this.currentId = currentId; }
    public String latestObservation() { return latestObservation; }
    public void latestObservation(String latestObservation) { this.latestObservation = latestObservation; }
    public StepResult latestStepResult() { return latestStepResult; }
    public DecisionResult latestDecisionResult() { return latestDecisionResult; }
    public CreateResult latestCreateResult() { return latestCreateResult; }
    public List<StepResult> stepHistory() { return List.copyOf(stepHistory); }
    public Map<String, Object> workingState() { return workingState; }
    public Instant startedAt() { return startedAt; }

    public void apply(StepResult result) {
        latestStepResult = result;
        latestObservation = result == null ? latestObservation : result.summary();
        if (result != null) {
            stepHistory.add(result);
            status = switch (String.valueOf(result.status())) {
                case "blocked" -> "blocked";
                case "failed" -> "failed";
                case "needs-coordinator" -> "needs-coordinator";
                case "needs-human" -> "needs-human";
                default -> "active";
            };
        }
    }

    public void apply(DecisionResult result) {
        latestDecisionResult = result;
    }

    public void apply(CreateResult result) {
        latestCreateResult = result;
    }
}
