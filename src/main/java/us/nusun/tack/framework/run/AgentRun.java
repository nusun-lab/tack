package us.nusun.tack.framework.run;

import us.nusun.tack.framework.routine.PrimitiveResult;
import us.nusun.tack.framework.routine.StepResult;

import java.time.Instant;
import java.util.Map;

public class AgentRun {
    public static final String STATUS_ACCEPTED = "accepted";
    public static final String STATUS_SCHEDULED = "scheduled";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETE = "complete";
    public static final String STATUS_FAILED = "failed";

    private final String id;
    private final String missionId;
    private final String parentAgentRunId;
    private final String personaId;
    private final String routineId;
    private final String subroutineId;
    private String status;
    private String currentPrimitive;
    private String currentId;
    private StepResult latestStepResult;
    private PrimitiveResult latestResult;
    private final Map<String, Object> workingState;
    private final Instant startedAt;

    public AgentRun(String id, String missionId, String personaId, String routineId, Map<String, Object> workingState) {
        this(id, missionId, null, personaId, routineId, null, workingState);
    }

    public AgentRun(String id, String missionId, String parentAgentRunId, String personaId, String routineId, Map<String, Object> workingState) {
        this(id, missionId, parentAgentRunId, personaId, routineId, null, workingState);
    }

    public AgentRun(String id, String missionId, String parentAgentRunId, String personaId, String routineId, String subroutineId, Map<String, Object> workingState) {
        this.id = id;
        this.missionId = missionId;
        this.parentAgentRunId = parentAgentRunId;
        this.personaId = personaId;
        this.routineId = routineId;
        this.subroutineId = subroutineId == null || subroutineId.isBlank() ? null : subroutineId.trim();
        this.status = STATUS_RUNNING;
        this.workingState = workingState == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(workingState);
        this.startedAt = Instant.now();
    }

    public String id() { return id; }
    public String missionId() { return missionId; }
    public String parentAgentRunId() { return parentAgentRunId; }
    public String personaId() { return personaId; }
    public String routineId() { return routineId; }
    public String subroutineId() { return subroutineId; }
    public String status() { return status; }
    public void status(String status) { this.status = status; }
    public String currentPrimitive() { return currentPrimitive; }
    public void currentPrimitive(String currentPrimitive) { this.currentPrimitive = currentPrimitive; }
    public String currentId() { return currentId; }
    public void currentId(String currentId) { this.currentId = currentId; }
    public StepResult latestStepResult() { return latestStepResult; }
    public PrimitiveResult latestResult() { return latestResult; }
    public Map<String, Object> workingState() { return workingState; }
    public Object input() { return workingState.get("input"); }
    public Instant startedAt() { return startedAt; }

    public void apply(PrimitiveResult result) {
        latestResult = result;
        if (result instanceof StepResult step) {
            latestStepResult = step;
        }
    }
}
