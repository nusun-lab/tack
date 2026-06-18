package us.nusun.llmshare.bean;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.Exchange;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ProjectMemoryStore {
    private final Map<String, String> projectBasicsByRequest = new ConcurrentHashMap<>();
    private final Map<String, String> scratchpadByRequestAndRole = new ConcurrentHashMap<>();

    public String projectKey(Exchange exchange) {
        Object requestId = exchange.getProperty("requestId");
        if (requestId != null) {
            return String.valueOf(requestId);
        }
        Object bodyRequestId = exchange.getProperty("bodyRequestId");
        if (bodyRequestId != null) {
            return String.valueOf(bodyRequestId);
        }
        return exchange.getExchangeId();
    }

    public String roleKey(Exchange exchange) {
        return projectKey(exchange) + "::" + exchange.getIn().getHeader("agent", String.class);
    }

    public String roleKey(Exchange exchange, String roleId) {
        return projectKey(exchange) + "::" + roleId;
    }

    public String getProjectBasics(Exchange exchange) {
        return projectBasicsByRequest.get(projectKey(exchange));
    }

    public void putProjectBasics(Exchange exchange, String value) {
        projectBasicsByRequest.put(projectKey(exchange), value);
    }

    public String getAgentScratchpad(Exchange exchange) {
        return scratchpadByRequestAndRole.get(roleKey(exchange));
    }

    public String getAgentScratchpad(Exchange exchange, String roleId) {
        return scratchpadByRequestAndRole.get(roleKey(exchange, roleId));
    }

    public void putAgentScratchpad(Exchange exchange, String value) {
        scratchpadByRequestAndRole.put(roleKey(exchange), value);
    }

    public void putAgentScratchpad(Exchange exchange, String roleId, String value) {
        scratchpadByRequestAndRole.put(roleKey(exchange, roleId), value);
    }

    public Map<String, String> snapshotProjectBasics() {
        return new LinkedHashMap<>(projectBasicsByRequest);
    }

    public Map<String, String> snapshotScratchpads() {
        return new LinkedHashMap<>(scratchpadByRequestAndRole);
    }

    public void clear() {
        projectBasicsByRequest.clear();
        scratchpadByRequestAndRole.clear();
    }
}
