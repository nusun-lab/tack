package us.nusun.llmshare.model;

public record ProjectBasicsStateRecord(
        String requestId,
        String projectBasics,
        String updatedByRoleId,
        String lastUpdatedAt
) {
}
