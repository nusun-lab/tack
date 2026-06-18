package us.nusun.llmshare.bean;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import us.nusun.llmshare.model.LlmJobResult;
import us.nusun.llmshare.model.RoutineStepResult;
import us.nusun.llmshare.model.RoutineToolTraceEntry;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class RoutineStepSupport {
    private static final String FORMATTER_ROLE_ID = "software-team/routine-formatter";
    private static final String FORMATTER_ROLE = "routine-formatter";

    @Inject
    ObjectMapper objectMapper;

    @Inject
    RoutineToolTraceTracker routineToolTraceTracker;

    public void prepareExploration(Exchange exchange) {
        UUID jobId = UUID.randomUUID();
        exchange.setProperty("routineExplorationJobId", jobId);
        exchange.setProperty("llmJobId", jobId);
        exchange.setProperty("routineExplorationSchemaApplied", false);
        exchange.setProperty("routineFormatterSchemaApplied", false);
        exchange.setProperty("routineFormatterAttempt", 0);
        exchange.setProperty("routineFormatterValidationError", null);
        exchange.setProperty("routineFormatterInvalidResponse", null);
        exchange.setProperty("routineRawAgentOutput", null);
        exchange.setProperty("routineFormatterRawOutput", null);
        exchange.setProperty("routineStepResult", null);
        exchange.setProperty("doneCandidate", false);
        exchange.setProperty("completionSignal", "continue");
        routineToolTraceTracker.prepare(jobId);
        exchange.getIn().removeHeader("responseSchema");
        exchange.getIn().setBody("""
                Objective: %s
                Original request: %s
                Cycle count: %s
                Routine step: %s
                Routine details: %s
                Previous summary: %s
                Available MCP services in this environment: Mattermost, Gitea, and Plane.
                Backstage MCP is not available in this environment. If this routine mentions Backstage, say that it is unavailable and continue with the available MCP services instead of pretending you used it.
                Before answering, use the available MCP tools relevant to this routine step.
                You must make at least one concrete MCP tool call when a relevant tool exists.
                Do not claim you opened, reviewed, updated, posted, checked, validated, or confirmed anything unless a real MCP tool call in this turn supports that statement.
                If no relevant tool exists or the tool call fails, say so plainly.
                Treat this step as exactly one small discrete task.
                Do not combine multiple actions in one step.
                Any tool-written text must be a small granular edit, never more than about 100 characters.
                Keep wiki content small, under 100 characters per page. Create linked sub-pages instead of large pages.
                After doing the work, return a concise plain-language result for this one routine step.
                Do not return JSON unless explicitly asked.
                """.formatted(
                stringProperty(exchange, "objective"),
                stringProperty(exchange, "requestPrompt"),
                stringProperty(exchange, "cycleCount"),
                stringProperty(exchange, "currentStep"),
                stringProperty(exchange, "currentRoutine"),
                stringProperty(exchange, "priorSummary")));
    }

    public void captureExploration(Exchange exchange) {
        LlmJobResult result = exchange.getIn().getBody(LlmJobResult.class);
        UUID jobId = exchange.getProperty("routineExplorationJobId", UUID.class);
        List<RoutineToolTraceEntry> toolTrace = routineToolTraceTracker.snapshot(jobId);
        routineToolTraceTracker.clear(jobId);

        exchange.setProperty("routineRawAgentOutput", result == null ? null : result.content());
        exchange.setProperty("routineRawAgentResponse", result == null ? null : result.rawResponse());
        exchange.setProperty("routineToolTrace", toolTrace);
        exchange.setProperty("routineToolTraceJson", toJson(toolTrace));
        exchange.setProperty("routineToolTraceCount", toolTrace.size());
        exchange.setProperty("routineToolUsed", !toolTrace.isEmpty());
        exchange.setProperty("routineToolServices", toolTrace.stream().map(RoutineToolTraceEntry::service).distinct().toList());
    }

    public void prepareFormatter(Exchange exchange) {
        exchange.setProperty("routineFormatterAttempt", 1);
        exchange.setProperty("routineFormatterValidationError", null);
        exchange.setProperty("routineFormatterInvalidResponse", null);
        overrideForFormatter(exchange);
    }

    public void captureFormatterFailure(Exchange exchange) {
        exchange.setProperty("routineFormatterValidationError", validationMessage(exchange));
        exchange.setProperty("routineFormatterInvalidResponse", extractRawFormatterResponse(exchange));
    }

    public void prepareFormatterRetry(Exchange exchange) {
        exchange.setProperty("routineFormatterAttempt", 2);
        overrideForFormatter(exchange);
    }

    public void captureFormatterResult(Exchange exchange) {
        LlmJobResult result = exchange.getIn().getBody(LlmJobResult.class);
        String raw = result == null ? exchange.getIn().getBody(String.class) : result.content();
        exchange.setProperty("routineFormatterRawOutput", raw);
        exchange.getIn().setBody(raw);
    }

    public void applyRoutineStepResult(Exchange exchange) {
        RoutineStepResult result = exchange.getIn().getBody(RoutineStepResult.class);
        exchange.setProperty("routineStepResult", result);
        exchange.setProperty("priorSummary", result.summary());
        exchange.setProperty("doneCandidate", result.doneCandidate());
        exchange.setProperty("needsFollowup", result.needsFollowup());
        exchange.setProperty("completionSignal", result.doneCandidate() ? "done-candidate" : "continue");

        String serialized = toJson(result);
        exchange.setProperty("lastRoutineResult", serialized);
        String dayResults = exchange.getProperty("dayRoutineResults", String.class);
        if (dayResults == null || dayResults.isBlank()) {
            exchange.setProperty("dayRoutineResults", serialized);
        } else {
            exchange.setProperty("dayRoutineResults", dayResults + "\n\n" + serialized);
        }
    }

    public void restoreRoleHeaders(Exchange exchange) {
        restoreHeader(exchange, "agent", "routineOriginalAgent");
        restoreHeader(exchange, "role", "routineOriginalRole");
        restoreHeader(exchange, "systemPrompt", "routineOriginalSystemPrompt");
        restoreHeader(exchange, "responseSchema", "routineOriginalResponseSchema");
        exchange.removeProperty("llmForceProviderSchema");
    }

    public void failFormatter(Exchange exchange) {
        throw new IllegalStateException("""
                Routine step formatter failed twice.
                step=%s
                routine=%s
                validationError=%s
                invalidResponse=%s
                rawAgentOutput=%s
                toolTrace=%s
                """.formatted(
                stringProperty(exchange, "currentStep"),
                stringProperty(exchange, "currentRoutine"),
                stringProperty(exchange, "routineFormatterValidationError"),
                stringProperty(exchange, "routineFormatterInvalidResponse"),
                stringProperty(exchange, "routineRawAgentOutput"),
                stringProperty(exchange, "routineToolTraceJson")));
    }

    private void overrideForFormatter(Exchange exchange) {
        saveOriginalHeader(exchange, "agent", "routineOriginalAgent");
        saveOriginalHeader(exchange, "role", "routineOriginalRole");
        saveOriginalHeader(exchange, "systemPrompt", "routineOriginalSystemPrompt");
        saveOriginalHeader(exchange, "responseSchema", "routineOriginalResponseSchema");

        UUID jobId = UUID.randomUUID();
        exchange.setProperty("routineFormatterJobId", jobId);
        exchange.setProperty("llmJobId", jobId);
        exchange.setProperty("llmForceProviderSchema", true);
        exchange.setProperty("routineFormatterSchemaApplied", true);
        exchange.getIn().setHeader("agent", FORMATTER_ROLE_ID);
        exchange.getIn().setHeader("role", FORMATTER_ROLE);
        exchange.getIn().setHeader("systemPrompt", """
                You are a strict structured formatter.
                You do not have tools.
                You do not invent facts.
                You summarize only from the provided routine context, raw agent output, and tool evidence.
                If the evidence is missing or ambiguous, say so explicitly in the structured fields instead of guessing.
                Return valid JSON only when asked for structured output.
                """);
        exchange.getIn().setHeader("responseSchema", "classpath:schemas/agent-routine-step-schema.json");
        exchange.getIn().setBody(formatterPrompt(exchange));
    }

    private String formatterPrompt(Exchange exchange) {
        String validationError = stringProperty(exchange, "routineFormatterValidationError");
        String invalidResponse = stringProperty(exchange, "routineFormatterInvalidResponse");
        String retrySection = validationError == null || validationError.isBlank()
                ? ""
                : """

                Your previous structured output was invalid.
                Validation error: %s
                Previous invalid response: %s
                Correct the JSON and try again.
                """.formatted(validationError, invalidResponse);

        return """
                Routine role: %s
                Routine step: %s
                Routine details: %s
                Objective: %s
                Original request: %s
                Previous summary: %s
                Raw agent output:
                %s

                Tool evidence JSON:
                %s
                %s
                Produce exactly one JSON object for Camel handoff with this shape:
                - role: the current routine role id
                - step: the current step id
                - routine: the current routine description
                - summary: one concise factual sentence about what happened in this step
                - nextAction: one concise factual sentence about the next move
                - actionsTaken: array of short factual action strings supported by the evidence
                - decisions: array of short factual decision strings supported by the evidence
                - openQuestions: array of short unresolved questions or limitations
                - artifactsTouched: array naming touched artifacts, services, channels, issues, repos, or pages
                - needsFollowup: boolean
                - doneCandidate: boolean
                Do not invent tool calls, decisions, or touched artifacts.
                If no real tool call happened, say so inside summary or openQuestions and keep actionsTaken grounded in the evidence.
                Return JSON only.
                """.formatted(
                stringProperty(exchange, "routineOriginalAgent"),
                stringProperty(exchange, "currentStep"),
                stringProperty(exchange, "currentRoutine"),
                stringProperty(exchange, "objective"),
                stringProperty(exchange, "requestPrompt"),
                stringProperty(exchange, "priorSummary"),
                stringProperty(exchange, "routineRawAgentOutput"),
                stringProperty(exchange, "routineToolTraceJson"),
                retrySection);
    }

    private String extractRawFormatterResponse(Exchange exchange) {
        Object body = exchange.getIn().getBody();
        if (body instanceof LlmJobResult result) {
            if (result.rawResponse() != null && !result.rawResponse().isBlank()) {
                return result.rawResponse();
            }
            return result.content();
        }
        return exchange.getIn().getBody(String.class);
    }

    private String validationMessage(Exchange exchange) {
        Exception failure = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
        return failure == null ? "Unknown formatter validation failure" : failure.getMessage();
    }

    private void saveOriginalHeader(Exchange exchange, String headerName, String propertyName) {
        if (exchange.getProperty(propertyName) == null) {
            exchange.setProperty(propertyName, exchange.getIn().getHeader(headerName));
        }
    }

    private void restoreHeader(Exchange exchange, String headerName, String propertyName) {
        Object value = exchange.getProperty(propertyName);
        if (value == null) {
            exchange.getIn().removeHeader(headerName);
        } else {
            exchange.getIn().setHeader(headerName, value);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize routine state", e);
        }
    }

    private static String stringProperty(Exchange exchange, String name) {
        Object value = exchange.getProperty(name);
        return value == null ? "" : String.valueOf(value);
    }
}
