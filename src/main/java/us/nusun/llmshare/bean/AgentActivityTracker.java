package us.nusun.llmshare.bean;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.Exchange;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class AgentActivityTracker {
    private final Map<String, AgentActivityState> activityByRole = new ConcurrentHashMap<>();

    public void markRouteStart(Exchange exchange) {
        update(exchange, true);
    }

    public void markWakeup(Exchange exchange) {
        update(exchange, true);
    }

    public void markStep(Exchange exchange) {
        update(exchange, true);
    }

    public void markSleep(Exchange exchange) {
        update(exchange, false);
    }

    public void markRouteComplete(Exchange exchange) {
        update(exchange, false);
    }

    public Map<String, AgentActivitySnapshot> snapshot() {
        LinkedHashMap<String, AgentActivitySnapshot> rows = new LinkedHashMap<>();
        activityByRole.forEach((roleId, state) -> rows.put(roleId, state.snapshot()));
        return rows;
    }

    public void clear() {
        activityByRole.clear();
    }

    private void update(Exchange exchange, boolean activeNow) {
        String roleId = exchange.getIn().getHeader("agent", String.class);
        if (blank(roleId) || "software-team/routine-formatter".equals(roleId)) {
            return;
        }
        activityByRole.computeIfAbsent(roleId, ignored -> new AgentActivityState())
                .update(
                        activeNow,
                        stringProperty(exchange, "requestId"),
                        stringProperty(exchange, "currentStep"),
                        stringProperty(exchange, "currentRoutine"),
                        exchange.getProperty("cycleCount", Integer.class),
                        exchange.getProperty("dayCount", Integer.class));
    }

    private static String stringProperty(Exchange exchange, String name) {
        Object value = exchange.getProperty(name);
        return value == null ? null : String.valueOf(value);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record AgentActivitySnapshot(
            boolean activeNow,
            String requestId,
            String currentStep,
            String currentRoutine,
            Integer cycleCount,
            Integer dayCount,
            String lastActivity
    ) {
    }

    private static final class AgentActivityState {
        private volatile boolean activeNow;
        private volatile String requestId;
        private volatile String currentStep;
        private volatile String currentRoutine;
        private volatile Integer cycleCount;
        private volatile Integer dayCount;
        private volatile Instant lastActivity;

        private synchronized void update(
                boolean activeNow,
                String requestId,
                String currentStep,
                String currentRoutine,
                Integer cycleCount,
                Integer dayCount
        ) {
            this.activeNow = activeNow;
            if (!blank(requestId)) {
                this.requestId = requestId;
            }
            if (!blank(currentStep) || activeNow) {
                this.currentStep = blank(currentStep) ? null : currentStep;
            }
            if (!blank(currentRoutine) || activeNow) {
                this.currentRoutine = blank(currentRoutine) ? null : currentRoutine;
            }
            if (cycleCount != null) {
                this.cycleCount = cycleCount;
            }
            if (dayCount != null) {
                this.dayCount = dayCount;
            }
            this.lastActivity = Instant.now();
            if (!activeNow) {
                this.currentStep = null;
                this.currentRoutine = null;
            }
        }

        private synchronized AgentActivitySnapshot snapshot() {
            return new AgentActivitySnapshot(
                    activeNow,
                    requestId,
                    currentStep,
                    currentRoutine,
                    cycleCount,
                    dayCount,
                    lastActivity == null ? null : lastActivity.toString());
        }
    }
}
