package us.nusun.llmshare.runtime;

public record AgentRuntimeDefinition(
        String roleId,
        String agentName,
        String agentConfigurationBeanName,
        String identityKey,
        String toolProfile
) {
}
