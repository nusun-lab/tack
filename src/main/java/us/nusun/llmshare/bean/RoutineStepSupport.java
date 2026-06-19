package us.nusun.llmshare.bean;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import us.nusun.llmshare.model.LlmJobResult;
import us.nusun.llmshare.model.ProjectCollaborationSetup;
import us.nusun.llmshare.model.RoutineStepResult;
import us.nusun.llmshare.model.RoutineToolTraceEntry;
import us.nusun.llmshare.team.AgentPersonaRegistry;
import us.nusun.llmshare.team.TeamOperatingContext;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class RoutineStepSupport {
    private static final String FORMATTER_ROLE_ID = "software-team/routine-formatter";
    private static final String PROJECT_MANAGER_ROLE_ID = "software-team/project-manager";
    private static final String FORMATTER_ROLE = "routine-formatter";
    private static final int MEMORY_LIMIT = 1000;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    RoutineToolTraceTracker routineToolTraceTracker;

    @Inject
    ProjectMemoryStore projectMemoryStore;

    @Inject
    AgentPersonaRegistry agentPersonaRegistry;

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
        ensureMemoryDefaults(exchange);
        loadSharedMemory(exchange);
        routineToolTraceTracker.prepare(jobId);
        exchange.getIn().removeHeader("responseSchema");
        exchange.getIn().setBody("""
                Objective: %s
                Original request: %s
                Cycle count: %s
                Routine step: %s
                Routine details: %s
                Previous summary: %s
                Known team roster:
                %s
                Team operating context:
                %s
                Project basics memory: %s
                Agent scratchpad memory: %s
                Available MCP services in this environment: %s.
                Unavailable tools in this environment: %s.
                Before answering, use the available MCP tools relevant to this routine step.
                You must make at least one concrete MCP tool call when a relevant tool exists.
                Do not claim you opened, reviewed, updated, posted, checked, validated, or confirmed anything unless a real MCP tool call in this turn supports that statement.
                If no relevant tool exists or the tool call fails, say so plainly.
                Treat this step as exactly one small discrete task.
                After doing the work, return a concise plain-language result for this one routine step.
                Do not return JSON unless explicitly asked.
                """.formatted(
                stringProperty(exchange, "objective"),
                stringProperty(exchange, "requestPrompt"),
                stringProperty(exchange, "cycleCount"),
                stringProperty(exchange, "currentStep"),
                stringProperty(exchange, "currentRoutine"),
                stringProperty(exchange, "priorSummary"),
                stringProperty(exchange, "teamRoster"),
                renderTeamContext(),
                stringProperty(exchange, "projectBasics"),
                stringProperty(exchange, "agentScratchpad"),
                String.join(", ", agentPersonaRegistry.teamContext().availableTools()),
                String.join(", ", agentPersonaRegistry.teamContext().unavailableTools())));
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
        exchange.setProperty("agentScratchpad", clampMemory(composeAgentScratchpad(result)));
        projectMemoryStore.putAgentScratchpad(
                exchange,
                resolveScratchpadOwnerRoleId(exchange, result),
                stringProperty(exchange, "agentScratchpad"));
        updateProjectBasicsForProjectManager(exchange, result);

        String serialized = toJson(result);
        exchange.setProperty("lastRoutineResult", serialized);
        String dayResults = exchange.getProperty("dayRoutineResults", String.class);
        if (dayResults == null || dayResults.isBlank()) {
            exchange.setProperty("dayRoutineResults", serialized);
        } else {
            exchange.setProperty("dayRoutineResults", dayResults + "\n\n" + serialized);
        }
    }

    public void prepareCollaborationSetupCheck(Exchange exchange) {
        ensureMemoryDefaults(exchange);
        exchange.getIn().setHeader("responseSchema", "classpath:schemas/project-collaboration-setup-schema.json");
        exchange.getIn().setBody("""
                Original request: %s
                Current project basics memory: %s
                Current routine result JSON:
                %s

                Judge only from the routine results and memory above whether team collaboration is set up.
                Collaboration is ready only if all of these are true:
                - a private Mattermost project channel exists or is confirmed
                - a private Plane project exists or is confirmed
                - a private Gitea org exists or is confirmed
                - the PM posted the exact Mattermost channel, Plane project, and Gitea org in #forum-agents
                - David Gordon (@aphelio) is included in the team collaboration spaces
                If a name is unknown, return an empty string for that field except Mattermost fallback may be #forum-agents.
                Return exactly one JSON object with:
                - collaborationReady
                - chatChannelReady
                - planeProjectReady
                - giteaOrgReady
                - forumAnnouncementReady
                - mattermostChannel
                - planeProject
                - giteaOrg
                - reason
                Return JSON only.
                """.formatted(
                stringProperty(exchange, "requestPrompt"),
                stringProperty(exchange, "projectBasics"),
                stringProperty(exchange, "dayRoutineResults")));
    }

    public void applyCollaborationSetupCheck(Exchange exchange) {
        ProjectCollaborationSetup result = exchange.getIn().getBody(ProjectCollaborationSetup.class);
        exchange.setProperty("collaborationSetupComplete", result != null && result.collaborationReady());
        exchange.setProperty("collaborationSetupReason", result == null ? "" : result.reason());
        if (result != null) {
            exchange.setProperty("mattermostChannel", safe(result.mattermostChannel()));
            exchange.setProperty("planeProject", safe(result.planeProject()));
            exchange.setProperty("giteaOrg", safe(result.giteaOrg()));
            exchange.setProperty("projectBasics", clampMemory(composeProjectBasics(result, stringProperty(exchange, "projectBasics"))));
            projectMemoryStore.putProjectBasics(exchange, stringProperty(exchange, "projectBasics"));
        }
        exchange.getIn().removeHeader("responseSchema");
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
        exchange.getIn().setHeader("systemPrompt", agentPersonaRegistry.getRequired(FORMATTER_ROLE_ID).systemPrompt());
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

    private void ensureMemoryDefaults(Exchange exchange) {
        if (blank(stringProperty(exchange, "projectBasics"))) {
            exchange.setProperty("projectBasics", "Use #forum-agents until exact channel, Plane project, and Gitea org are confirmed.");
        }
        if (blank(stringProperty(exchange, "agentScratchpad"))) {
            exchange.setProperty("agentScratchpad", "No agent notes yet.");
        }
        if (blank(stringProperty(exchange, "teamRoster"))) {
            exchange.setProperty("teamRoster", agentPersonaRegistry.directorTeamRoster());
        }
        if (exchange.getProperty("collaborationSetupComplete") == null) {
            exchange.setProperty("collaborationSetupComplete", false);
        }
    }

    private String renderTeamContext() {
        TeamOperatingContext context = agentPersonaRegistry.teamContext();
        return """
                team=%s
                owner=%s (%s)
                defaultChannel=%s
                rules=%s
                constraints=%s
                """.formatted(
                context.teamDisplayName(),
                context.ownerDisplayName(),
                context.ownerIdentity(),
                context.defaultCoordinationChannel(),
                String.join(" | ", context.collaborationRules()),
                String.join(" | ", context.sharedConstraints()));
    }

    private void loadSharedMemory(Exchange exchange) {
        String sharedProjectBasics = projectMemoryStore.getProjectBasics(exchange);
        if (!blank(sharedProjectBasics)) {
            exchange.setProperty("projectBasics", sharedProjectBasics);
        }
        String sharedScratchpad = projectMemoryStore.getAgentScratchpad(exchange, resolveScratchpadOwnerRoleId(exchange, null));
        if (!blank(sharedScratchpad)) {
            exchange.setProperty("agentScratchpad", sharedScratchpad);
        }
    }

    private static String resolveScratchpadOwnerRoleId(Exchange exchange, RoutineStepResult result) {
        String originalAgent = stringProperty(exchange, "routineOriginalAgent");
        if (!blank(originalAgent)) {
            return originalAgent;
        }
        if (result != null && !blank(result.role()) && !FORMATTER_ROLE_ID.equals(result.role())) {
            return result.role();
        }
        String currentAgent = exchange.getIn().getHeader("agent", String.class);
        if (!blank(currentAgent)) {
            return currentAgent;
        }
        return FORMATTER_ROLE_ID;
    }

    private void updateProjectBasicsForProjectManager(Exchange exchange, RoutineStepResult result) {
        String ownerRoleId = resolveScratchpadOwnerRoleId(exchange, result);
        if (!PROJECT_MANAGER_ROLE_ID.equals(ownerRoleId)) {
            return;
        }
        String currentBasics = stringProperty(exchange, "projectBasics");
        if (blank(currentBasics)) {
            currentBasics = "Use #forum-agents until exact channel, Plane project, and Gitea org are confirmed.";
            exchange.setProperty("projectBasics", currentBasics);
        }
        if (isProjectBasicsStep(exchange)) {
            projectMemoryStore.putProjectBasics(exchange, clampMemory(currentBasics));
        }
    }

    private static boolean isProjectBasicsStep(Exchange exchange) {
        return switch (stringProperty(exchange, "currentStep")) {
            case "forum-fallback-check",
                 "chat-channel-setup",
                 "plane-project-setup",
                 "gitea-org-setup",
                 "forum-announcement",
                 "collaboration-setup-gap" -> true;
            default -> false;
        };
    }

    private static String composeAgentScratchpad(RoutineStepResult result) {
        return safe(result.step()) + ": " + safe(result.summary()) + " Next: " + safe(result.nextAction());
    }

    private static String composeProjectBasics(ProjectCollaborationSetup result, String fallback) {
        StringBuilder basics = new StringBuilder();
        if (!blank(result.mattermostChannel())) {
            basics.append("chat=").append(result.mattermostChannel());
        }
        if (!blank(result.planeProject())) {
            appendWithSeparator(basics, "plane=" + result.planeProject());
        }
        if (!blank(result.giteaOrg())) {
            appendWithSeparator(basics, "gitea=" + result.giteaOrg());
        }
        appendWithSeparator(basics, result.collaborationReady() ? "private ready include @aphelio" : "fallback #forum-agents");
        appendWithSeparator(basics, result.reason());
        String built = basics.toString().trim();
        return blank(built) ? fallback : built;
    }

    private static void appendWithSeparator(StringBuilder builder, String value) {
        if (blank(value)) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append(" | ");
        }
        builder.append(value);
    }

    private static String clampMemory(String value) {
        String normalized = safe(value).replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MEMORY_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, MEMORY_LIMIT - 1).trim() + "…";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
