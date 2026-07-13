package us.nusun.llmshare.framework.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.camel.Exchange;
import us.nusun.llmshare.framework.model.Mission;
import us.nusun.llmshare.framework.model.MissionRunCapacity;
import us.nusun.llmshare.framework.state.AgentRun;
import us.nusun.llmshare.framework.state.AgentRunStore;

@ApplicationScoped
@Named("agentRunCapacityService")
public class AgentRunCapacityService {
    @Inject AgentRunStore agentRuns;

    public AgentRunCapacityService() {
    }

    public AgentRunCapacityService(AgentRunStore agentRuns) {
        this.agentRuns = agentRuns;
    }

    public int activeRunCount(String missionId) {
        return agentRuns.activeRunCount(missionId);
    }

    public int countForCurrentMission(Exchange exchange) {
        String missionId = missionId(exchange);
        return missionId == null ? 0 : activeRunCount(missionId);
    }

    public MissionRunCapacity capacity(String missionId, int maximum) {
        return new MissionRunCapacity(missionId, maximum, activeRunCount(missionId), 0);
    }

    public MissionRunCapacity capacityForCurrentMission(Exchange exchange) {
        String missionId = missionId(exchange);
        Integer maximum = exchange == null ? null : exchange.getProperty(AgentRunAdmissionService.CAPACITY_MAXIMUM_PROPERTY, Integer.class);
        if (missionId == null) {
            throw new IllegalArgumentException("Capacity query requires current mission");
        }
        if (maximum == null) {
            throw new IllegalArgumentException("Capacity query requires " + AgentRunAdmissionService.CAPACITY_MAXIMUM_PROPERTY + " exchange property");
        }
        return capacity(missionId, maximum);
    }

    private static String missionId(Exchange exchange) {
        if (exchange == null) {
            return null;
        }
        Mission mission = exchange.getProperty("mission", Mission.class);
        if (mission != null && mission.id() != null && !mission.id().isBlank()) {
            return mission.id();
        }
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        return run == null ? null : run.missionId();
    }
}
