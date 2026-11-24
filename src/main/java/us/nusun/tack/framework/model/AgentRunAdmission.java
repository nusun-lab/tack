package us.nusun.tack.framework.model;

public record AgentRunAdmission(
        AgentRunHandle handle,
        AgentRunScheduled event
) {
    public AgentRunAdmission {
        if (handle == null) {
            throw new IllegalArgumentException("AgentRunAdmission missing handle");
        }
        if (event == null) {
            throw new IllegalArgumentException("AgentRunAdmission missing event");
        }
    }
}
