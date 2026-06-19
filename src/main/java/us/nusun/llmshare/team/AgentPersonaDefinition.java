package us.nusun.llmshare.team;

import java.util.List;
import java.util.Map;

public record AgentPersonaDefinition(
        String roleId,
        String roleSlug,
        String displayName,
        String routeUri,
        String routeId,
        String agentLabel,
        String ansiColor,
        String wakeupMessage,
        String sleepMessage,
        String capabilitySummary,
        String systemPrompt,
        Map<String, String> platformIdentities,
        List<RoutineStepDefinition> routineSteps,
        boolean teamMember,
        boolean assignableByDirector
) {
    public AgentPersonaDefinition {
        platformIdentities = platformIdentities == null ? Map.of() : Map.copyOf(platformIdentities);
        routineSteps = routineSteps == null ? List.of() : List.copyOf(routineSteps);
    }
}
