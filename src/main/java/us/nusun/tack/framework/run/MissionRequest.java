package us.nusun.tack.framework.run;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MissionRequest(
        String goal,
        List<AgentRunRequest> agentRuns
) {
    public MissionRequest {
        agentRuns = agentRuns == null ? List.of() : List.copyOf(agentRuns);
    }
}
