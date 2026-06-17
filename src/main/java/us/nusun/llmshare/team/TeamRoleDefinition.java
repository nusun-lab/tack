package us.nusun.llmshare.team;

public record TeamRoleDefinition(
        String roleId,
        String displayName,
        String routeUri,
        String agentLabel,
        String wakeupMessage,
        String sleepMessage,
        String capabilitySummary,
        String systemPrompt
) {
}
