package us.nusun.llmshare.team;

import java.util.List;

public record TeamOperatingContext(
        String teamId,
        String teamDisplayName,
        String ownerDisplayName,
        String ownerIdentity,
        String defaultCoordinationChannel,
        List<String> availableTools,
        List<String> unavailableTools,
        List<String> collaborationRules,
        List<String> sharedConstraints
) {
    public TeamOperatingContext {
        availableTools = availableTools == null ? List.of() : List.copyOf(availableTools);
        unavailableTools = unavailableTools == null ? List.of() : List.copyOf(unavailableTools);
        collaborationRules = collaborationRules == null ? List.of() : List.copyOf(collaborationRules);
        sharedConstraints = sharedConstraints == null ? List.of() : List.copyOf(sharedConstraints);
    }
}
