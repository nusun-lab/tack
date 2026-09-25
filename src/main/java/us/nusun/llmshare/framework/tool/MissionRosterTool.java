package us.nusun.llmshare.framework.tool;

import dev.langchain4j.agent.tool.Tool;
import us.nusun.llmshare.framework.service.MissionRosterService;

public final class MissionRosterTool {
    private final MissionRosterService roster;
    private final String missionId;

    public MissionRosterTool(MissionRosterService roster, String missionId) {
        this.roster = roster;
        this.missionId = missionId;
    }

    @Tool("Read the authoritative current active AgentRun roster for this mission, including each active persona, routine/subroutine, run hierarchy, focused input, status, and prompt-safe platform identities. Use this when current teammate coverage, assignment identity, onboarding, or access reconciliation matters.")
    public String mission_roster() {
        return roster.activeMissionRosterPrompt(missionId);
    }
}
