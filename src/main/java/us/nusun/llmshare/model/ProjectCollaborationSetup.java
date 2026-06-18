package us.nusun.llmshare.model;

public record ProjectCollaborationSetup(
        boolean collaborationReady,
        boolean chatChannelReady,
        boolean planeProjectReady,
        boolean giteaOrgReady,
        boolean forumAnnouncementReady,
        String mattermostChannel,
        String planeProject,
        String giteaOrg,
        String reason
) {
}
