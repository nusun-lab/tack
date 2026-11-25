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
    private volatile String status;
    private String currentPrimitive;
    private String currentId;
    private StepResult latestStepResult;
    private PrimitiveResult latestResult;
    private final Map<String, Object> input;
    private String sandboxName;
    private final Instant startedAt;

    public AgentRun(String id, String missionId, String parentAgentRunId, AgentRunRequest request) {
        this.id = id;
        this.missionId = missionId;
        this.parentAgentRunId = parentAgentRunId;
        this.personaId = request.personaId();
        this.routineId = request.routineId();
        this.subroutineId = request.subroutineId();
        this.status = STATUS_RUNNING;
        this.input = request.input().isEmpty() ? null : request.input();
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
    public Map<String, Object> input() { return input; }
    public String sandboxName() { return sandboxName; }
    public void sandboxName(String sandboxName) { this.sandboxName = sandboxName; }
    public Instant startedAt() { return startedAt; }

    public void apply(PrimitiveResult result) {
        latestResult = result;
        if (result instanceof StepResult step) {
            latestStepResult = step;
        }
    }
}
