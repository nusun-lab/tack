package us.nusun.llmshare.framework.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import us.nusun.llmshare.framework.service.MissionRosterService;

public final class MissionRosterTool {
    private final MissionRosterService roster;
    private final String missionId;

    public MissionRosterTool(MissionRosterService roster, String missionId) {
        this.roster = roster;
        this.missionId = missionId;
    }

    @Tool("""
            Read mission roster information. Defaults to the active roster in a compact contacts view with names,
            roles, statuses, and tool handles. Use scope=history for past and current AgentRuns; history defaults to
            a compact run view. Use view=coverage for staffing checks, contacts for cross-tool handles, runs for run
            history, or full only when detailed run input/subroutine context is actually needed. Optional personaId,
            routineId, and comma-separated platforms filters narrow the result.
            """)
    public String mission_roster(
            @P(value = "active (default) or history", required = false) String scope,
            @P(value = "coverage, contacts, runs, or full; defaults to contacts for active and runs for history", required = false)
            String view,
            @P(value = "Optional exact persona id filter", required = false) String personaId,
            @P(value = "Optional exact routine id filter", required = false) String routineId,
            @P(value = "Optional comma-separated platform filter such as mattermost,plane,gitea", required = false)
            String platforms) {
        return roster.rosterView(missionId, scope, view, personaId, routineId, platforms);
    }
}
