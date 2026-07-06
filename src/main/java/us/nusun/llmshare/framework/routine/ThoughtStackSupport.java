package us.nusun.llmshare.framework.routine;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import us.nusun.llmshare.framework.model.ThoughtFrame;
import us.nusun.llmshare.framework.projection.AgentRunProjectionManager;
import us.nusun.llmshare.framework.state.ThoughtStack;

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
            stack(exchange).pop();
            record(exchange, "THOUGHT_POPPED");
        };
    }

    public ThoughtFrame push(Exchange exchange, String context, int maxTokens) {
        return push(exchange, null, context, maxTokens);
    }

    public ThoughtFrame push(Exchange exchange, String name, String context, int maxTokens) {
        ThoughtFrame frame = new ThoughtFrame("t_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8), name, context, maxTokens, "");
        stack(exchange).push(frame);
        record(exchange, "THOUGHT_PUSHED");
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

    private void record(Exchange exchange, String eventType) {
        if (projections != null) {
            projections.record(exchange, eventType);
        }
    }
}
