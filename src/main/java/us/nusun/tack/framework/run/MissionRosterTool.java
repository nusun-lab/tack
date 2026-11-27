package us.nusun.tack.framework.run;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public final class MissionRosterTool {
    private final MissionRosterService roster;
    private final String missionId;
    private final Integer capacityMaximum;

    public MissionRosterTool(MissionRosterService roster, String missionId, Integer capacityMaximum) {
        this.roster = roster;
        this.missionId = missionId;
        this.capacityMaximum = capacityMaximum;
    }

    @Tool("""
            Read mission roster information. The default active contacts view is best for teammate names and tool
            handles. Use coverage for current staffing/role coverage and mission run capacity, history/runs for prior
            AgentRuns, and full only when detailed run input/subroutine context is necessary. Optional personaId,
            routineId, and comma-separated platforms filters narrow the result. Prefer the smallest view that answers
            the current act.
            """)
    public String mission_roster(
            @P(value = "active (default) or history", required = false) String scope,
            @P(value = "coverage, contacts, runs, or full; defaults to contacts for active and runs for history", required = false)
            String view,
            @P(value = "Optional exact persona id filter", required = false) String personaId,
            @P(value = "Optional exact routine id filter", required = false) String routineId,
            @P(value = "Optional comma-separated platform filter such as mattermost,gitea", required = false)
            String platforms) {
        return roster.rosterView(missionId, scope, view, personaId, routineId, platforms, capacityMaximum);
    }
}
