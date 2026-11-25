package us.nusun.tack.framework.run;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentRunHandle(
        String agentRunId,
        String missionId,
        String parentAgentRunId,
        String personaId,
        String routineId,
        String subroutineId,
        String status
) {
}
