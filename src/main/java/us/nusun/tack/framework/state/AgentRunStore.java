package us.nusun.tack.framework.state;

import jakarta.enterprise.context.ApplicationScoped;
import us.nusun.tack.framework.model.AgentRunScheduled;
import us.nusun.tack.framework.model.Mission;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class AgentRunStore {
    private final ConcurrentHashMap<String, Mission> missions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AgentRun> runs = new ConcurrentHashMap<>();

    public synchronized void putMission(Mission mission) {
        if (mission != null) {
            missions.put(mission.id(), mission);
        }
    }

    public synchronized void putRun(AgentRun run) {
        if (run != null) {
            runs.put(run.id(), run);
        }
    }

    public Mission getMission(String missionId) {
        return missionId == null ? null : missions.get(missionId);
    }

    public AgentRun getRun(String agentRunId) {
        return agentRunId == null ? null : runs.get(agentRunId);
    }

    public List<Mission> missions() {
        return List.copyOf(missions.values());
    }

    public List<AgentRun> runs() {
        return List.copyOf(runs.values());
    }

    public List<AgentRun> runsByMission(String missionId) {
        if (missionId == null || missionId.isBlank()) {
            return List.of();
        }
        return runs.values().stream()
                .filter(run -> missionId.equals(run.missionId()))
                .sorted(java.util.Comparator.comparing(AgentRun::id))
                .toList();
    }

    public synchronized int activeRunCount(String missionId) {
        if (missionId == null || missionId.isBlank()) {
            return 0;
        }
        return (int) runs.values().stream()
                .filter(run -> missionId.equals(run.missionId()))
                .filter(run -> isActiveStatus(run.status()))
                .count();
    }

    public static boolean isActiveStatus(String status) {
        return AgentRun.STATUS_ACCEPTED.equals(status)
                || AgentRun.STATUS_SCHEDULED.equals(status)
                || AgentRun.STATUS_RUNNING.equals(status);
    }

    public Mission requireMission(String missionId) {
        Mission mission = getMission(missionId);
        if (mission == null) {
            throw new IllegalStateException("AgentRun mission state not found: " + missionId);
        }
        return mission;
    }

    public AgentRun requireRun(AgentRunScheduled scheduled) {
        AgentRun run = getRun(scheduled.agentRunId());
        if (run == null) {
            throw new IllegalStateException("AgentRun state not found: " + scheduled.agentRunId());
        }
        if (!scheduled.missionId().equals(run.missionId())) {
            throw new IllegalStateException("AgentRun " + scheduled.agentRunId()
                    + " belongs to mission " + run.missionId()
                    + " not scheduled mission " + scheduled.missionId());
        }
        return run;
    }

    public boolean startIfEligible(AgentRunScheduled scheduled) {
        AgentRun run = getRun(scheduled.agentRunId());
        if (run == null || !scheduled.missionId().equals(run.missionId())) {
            return false;
        }
        synchronized (run) {
            if (AgentRun.STATUS_ACCEPTED.equals(run.status()) || AgentRun.STATUS_SCHEDULED.equals(run.status())) {
                run.status(AgentRun.STATUS_RUNNING);
                return true;
            }
            return false;
        }
    }

    public void clear() {
        missions.clear();
        runs.clear();
    }
}
