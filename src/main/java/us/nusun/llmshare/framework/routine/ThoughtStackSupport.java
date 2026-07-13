package us.nusun.llmshare.framework.routine;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import us.nusun.llmshare.framework.model.ThoughtFrame;
import us.nusun.llmshare.framework.projection.AgentRunProjectionManager;
import us.nusun.llmshare.framework.state.ThoughtStack;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class ThoughtStackSupport {
    public static final String STACK_PROPERTY = "thoughtStack";

    @Inject AgentRunProjectionManager projections;

    public Processor push(String context, int maxTokens) {
        return exchange -> push(exchange, null, context, maxTokens);
    }

    public Processor push(String name, String context, int maxTokens) {
        return exchange -> push(exchange, name, context, maxTokens);
    }

    public Processor pop() {
        return exchange -> {
            ThoughtFrame popped = stack(exchange).pop();
            record(exchange, "THOUGHT_EXITED", thoughtDetails(popped));
        };
    }

    public ThoughtFrame push(Exchange exchange, String context, int maxTokens) {
        return push(exchange, null, context, maxTokens);
    }

    public ThoughtFrame push(Exchange exchange, String name, String context, int maxTokens) {
        if (context == null || context.isBlank()) {
            throw new IllegalArgumentException("thought purpose is required");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("thought maxTokens must be positive");
        }
        ThoughtFrame frame = new ThoughtFrame("t_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8), name, context, maxTokens, "", Instant.now());
        stack(exchange).push(frame);
        record(exchange, "THOUGHT_ENTERED", thoughtDetails(frame));
        return frame;
    }

    public ThoughtStack stack(Exchange exchange) {
        ThoughtStack stack = exchange.getProperty(STACK_PROPERTY, ThoughtStack.class);
        if (stack == null) {
            stack = new ThoughtStack();
            exchange.setProperty(STACK_PROPERTY, stack);
        }
        return stack;
    }

    private void record(Exchange exchange, String eventType, Map<String, Object> details) {
        if (projections != null) {
            projections.record(exchange, eventType, details);
        }
    }

    private static Map<String, Object> thoughtDetails(ThoughtFrame frame) {
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        details.put("thoughtName", frame.name());
        details.put("thoughtId", frame.id());
        details.put("purpose", frame.context());
        details.put("maxTokens", frame.maxTokens());
        details.put("content", frame.content());
        details.put("timestamp", Instant.now().toString());
        return details;
    }
}
