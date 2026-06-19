package us.nusun.llmshare.model;

public record ScratchpadStateRecord(
        String requestId,
        String roleId,
        String scratchpad,
        int messageCount,
        String lastUpdatedAt
) {
}
