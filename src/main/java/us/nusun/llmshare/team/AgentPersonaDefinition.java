package us.nusun.llmshare.team;

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
        boolean teamMember,
        boolean assignableByDirector
) {
    public AgentPersonaDefinition {
        platformIdentities = platformIdentities == null ? Map.of() : Map.copyOf(platformIdentities);
    }
}
