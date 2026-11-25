package us.nusun.tack.framework.routine;

import org.apache.camel.Exchange;
import org.apache.camel.support.DefaultProducer;

public class TackProducer extends DefaultProducer {
    private final TackEndpoint endpoint;
    private final PrimitiveExecutor executor;

    public TackProducer(TackEndpoint endpoint, PrimitiveExecutor executor) {
        super(endpoint);
        this.endpoint = endpoint;
        this.executor = executor;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        if (executor == null) {
            throw new IllegalStateException("No PrimitiveExecutor is configured for tack component");
        }
        String operation = requiredOperation();
        switch (operation) {
            case "act" -> executor.act(exchange, new PrimitiveExecutor.ActRequest(
                    requiredParameter("id", endpoint.getId(), operation),
                    consumeRequiredHeader(exchange, TackHeaders.PROMPT, operation),
                    endpoint.getToolProfile(),
                    endpoint.getLane()));
            case "decide" -> executor.decide(exchange, new PrimitiveExecutor.DecideRequest(
                    requiredParameter("id", endpoint.getId(), operation),
                    consumeRequiredHeader(exchange, TackHeaders.PROMPT, operation),
                    endpoint.getChoices(),
                    endpoint.getLane()));
            case "create" -> executor.create(exchange, new PrimitiveExecutor.CreateRequest(
                    requiredParameter("id", endpoint.getId(), operation),
                    consumeRequiredHeader(exchange, TackHeaders.PROMPT, operation),
                    endpoint.getSchemaId(),
                    endpoint.getSchema(),
                    endpoint.getLane()));
            case "thought-enter" -> executor.enterThought(exchange, new PrimitiveExecutor.ThoughtEnterRequest(
                    requiredParameter("name", endpoint.getName(), operation),
                    consumeRequiredHeader(exchange, TackHeaders.THOUGHT_PURPOSE, operation),
                    requiredPositiveInteger("maxTokens", endpoint.getMaxTokens(), operation)));
            case "thought-exit" -> executor.exitThought(exchange, new PrimitiveExecutor.ThoughtExitRequest(
                    requiredParameter("name", endpoint.getName(), operation),
                    endpoint.getLane()));
            default -> throw new IllegalArgumentException("Unknown tack operation: " + operation);
        }
    }

    private String requiredOperation() {
        String operation = endpoint.getOperation();
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("Missing required operation for tack endpoint");
        }
        return operation.strip();
    }

    private static String requiredParameter(String name, String value, String operation) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required " + name + " parameter for tack:" + operation);
        }
        return value.strip();
    }

    private static int requiredPositiveInteger(String name, Integer value, String operation) {
        if (value == null) {
            throw new IllegalArgumentException("Missing required " + name + " parameter for tack:" + operation);
        }
        if (value <= 0) {
            throw new IllegalArgumentException("Parameter " + name + " for tack:" + operation + " must be positive");
        }
        return value;
    }

    private static String consumeRequiredHeader(Exchange exchange, String header, String operation) {
        Object raw = exchange.getMessage().getHeader(header);
        exchange.getMessage().removeHeader(header);
        String text = raw == null ? null : String.valueOf(raw).strip();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Missing required " + header + " header for tack:" + operation);
        }
        return text;
    }
}
