package us.nusun.llmshare.framework.exchange;

import org.apache.camel.Exchange;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AgentRunExchangeScope {
    static final String PROPERTY = "agentRunExchangeScopeStack";

    private static final List<String> AGENT_RUN_LOCAL_PROPERTIES = List.of(
            "agentRun",
            "projectionTimeline",
            "thoughtStack",
            "routineThoughtScopeNames",
            "actId",
            "actPrompt",
            "decisionId",
            "decisionPrompt",
            "createId",
            "createPrompt",
            "createSchema",
            "createSchemaRaw",
            "createSchemaJson",
            "createSchemaUri",
            "schemaId",
            "toolProfile",
            "toolRuntime",
            "synthesisRuntime",
            "resolvedToolRuntime",
            "resolvedSynthesisRuntime",
            "toolPassResult",
            "latestPrimitiveFailure",
            "latestStepResult",
            "latestDecisionResult",
            "latestCreateResult",
            "createdAgentRun",
            "childAgentRunSetupComplete",
            "childAgentRunResponse",
            "childAgentRunFailure",
            "childAgentRunSetupFailure",
            "executionRuntimeId",
            "personaId",
            "thoughtUpdateDepth",
            "agentRunRouteUri");

    private static final List<String> AGENT_RUN_LOCAL_PREFIXES = List.of(
            "creates.",
            "decisions.",
            "decision.");

    private final Map<String, Object> captured;

    private AgentRunExchangeScope(Map<String, Object> captured) {
        this.captured = captured;
    }

    public static AgentRunExchangeScope captureAndClear(Exchange exchange) {
        Map<String, Object> captured = new LinkedHashMap<>();
        for (String property : AGENT_RUN_LOCAL_PROPERTIES) {
            if (exchange.getProperties().containsKey(property)) {
                captured.put(property, exchange.getProperty(property));
            }
            exchange.removeProperty(property);
        }
        for (String property : List.copyOf(exchange.getProperties().keySet())) {
            if (isAgentRunLocalPrefix(property)) {
                captured.put(property, exchange.getProperty(property));
                exchange.removeProperty(property);
            }
        }
        AgentRunExchangeScope scope = new AgentRunExchangeScope(captured);
        stack(exchange).push(scope);
        return scope;
    }

    public static void restore(Exchange exchange) {
        Deque<AgentRunExchangeScope> stack = existingStack(exchange);
        if (stack == null || stack.isEmpty()) {
            return;
        }
        AgentRunExchangeScope scope = stack.pop();
        for (String property : AGENT_RUN_LOCAL_PROPERTIES) {
            exchange.removeProperty(property);
        }
        for (String property : List.copyOf(exchange.getProperties().keySet())) {
            if (isAgentRunLocalPrefix(property)) {
                exchange.removeProperty(property);
            }
        }
        for (Map.Entry<String, Object> entry : scope.captured.entrySet()) {
            exchange.setProperty(entry.getKey(), entry.getValue());
        }
        if (stack.isEmpty()) {
            exchange.removeProperty(PROPERTY);
        }
    }

    @SuppressWarnings("unchecked")
    private static Deque<AgentRunExchangeScope> existingStack(Exchange exchange) {
        return exchange.getProperty(PROPERTY, Deque.class);
    }

    private static Deque<AgentRunExchangeScope> stack(Exchange exchange) {
        Deque<AgentRunExchangeScope> stack = existingStack(exchange);
        if (stack == null) {
            stack = new ArrayDeque<>();
            exchange.setProperty(PROPERTY, stack);
        }
        return stack;
    }

    private static boolean isAgentRunLocalPrefix(String property) {
        for (String prefix : AGENT_RUN_LOCAL_PREFIXES) {
            if (property.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
