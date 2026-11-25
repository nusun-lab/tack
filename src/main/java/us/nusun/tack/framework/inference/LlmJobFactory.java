package us.nusun.tack.framework.inference;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.tack.framework.inference.lane.ModelConfiguration;
import us.nusun.tack.framework.inference.lane.InferenceLaneRegistry;
import us.nusun.tack.framework.persona.PersonaCatalog;
import us.nusun.tack.framework.run.Mission;
import us.nusun.tack.framework.persona.Persona;
import us.nusun.tack.framework.sandbox.PersonaSandboxManager;
import us.nusun.tack.framework.run.AgentRun;

import java.time.Instant;
import java.util.UUID;

import static us.nusun.tack.framework.inference.LlmExecutionContext.FORCE_SCHEMA;
import static us.nusun.tack.framework.inference.LlmExecutionContext.PROPERTY;
import static us.nusun.tack.framework.inference.LlmExecutionContext.flag;
import static us.nusun.tack.framework.inference.LlmExecutionContext.integer;
import static us.nusun.tack.framework.inference.LlmExecutionContext.label;
import static us.nusun.tack.framework.inference.LlmExecutionContext.propertyOrHeader;
import static us.nusun.tack.framework.inference.LlmExecutionContext.text;
import static us.nusun.tack.framework.FrameworkText.firstNonBlank;
import static us.nusun.tack.framework.FrameworkText.firstNonBlankStripped;
import static us.nusun.tack.framework.run.AgentRunAdmissionService.CAPACITY_MAXIMUM_PROPERTY;

@ApplicationScoped
public class LlmJobFactory {
    @Inject InferenceLaneRegistry lanes;
    @Inject PersonaCatalog personas;
    String defaultModel;
    boolean providerSchemaDefault;
    @ConfigProperty(name = "agent.framework.default-lane", defaultValue = "low-pass")
    String defaultLane;

    @Inject
    void configureModelDefaults(ModelConfiguration configuration) {
        defaultModel = configuration.openai().model();
        providerSchemaDefault = configuration.structuredOutput().providerSchemaEnabled();
    }

    public void prepare(Exchange exchange) {
        exchange.removeProperty(PROPERTY);
        exchange.removeProperty("llmJob");
        if (!(exchange.getMessage().getBody() instanceof LlmJobEnvelope request)) {
            throw new IllegalArgumentException("direct:llm requires an LlmJobEnvelope");
        }
        exchange.setProperty("llmJob", request);

        var lane = lanes.getRequired(firstNonBlankStripped(request.laneId(), defaultLane));
        var run = exchange.getProperty("agentRun", AgentRun.class);
        var mission = exchange.getProperty("mission", Mission.class);
        var metadata = request.metadata();
        var requester = firstNonBlankStripped(request.requester(), "framework");
        var personaId = run == null ? text(metadata, "personaId") : run.personaId();
        var persona = persona(personaId);
        var sandbox = run == null ? null : run.workingState().get(PersonaSandboxManager.WORKING_STATE_SANDBOX_NAME);
        var force = flag(propertyOrHeader(exchange, FORCE_SCHEMA));
        if (force == null) {
            force = flag(metadata.get(FORCE_SCHEMA));
        }
        var capacity = integer(propertyOrHeader(exchange, CAPACITY_MAXIMUM_PROPERTY));

        var context = new LlmExecutionContext(
                request.jobId() == null ? UUID.randomUUID() : request.jobId(), lane.id(),
                request.passType() == null ? LlmPassType.SYNTHESIS : request.passType(),
                firstNonBlank(lane.model(), defaultModel), lane.partitionKeyStrategy(),
                firstNonBlank(mission == null ? null : mission.id(), text(metadata, "missionId")),
                run == null ? text(metadata, "agentRunId") : run.id(), personaId, text(metadata, "toolProfile"),
                firstNonBlank(sandbox instanceof String name ? name : null, text(metadata, "sandboxName")),
                run == null ? text(metadata, "primitive") : run.currentPrimitive(),
                run == null ? text(metadata, "primitiveId") : run.currentId(), text(metadata, "primitivePrompt"),
                capacity == null ? integer(metadata.get(CAPACITY_MAXIMUM_PROPERTY)) : capacity,
                providerSchemaDefault || Boolean.TRUE.equals(force),
                label(persona, requester), persona == null ? null : persona.color());
        exchange.setProperty(PROPERTY, context);
        if (request.prompt() == null || request.prompt().isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }
        LlmJobEnvelope queued = new LlmJobEnvelope(context.jobId(), LlmJobStatus.QUEUED, context.laneId(), context.passType(),
                requester, firstNonBlankStripped(request.role(), "framework"), request.priority(), request.prompt(),
                systemPrompt(request), request.responseSchema(), context.wireMetadata(metadata, force),
                request.requestedAt() == null ? Instant.now() : request.requestedAt());
        exchange.setProperty("llmJob", queued);
        exchange.setProperty("laneRequestTopic", lane.requestTopic());
        exchange.getMessage().setBody(queued);
    }

    public void restore(Exchange exchange) {
        exchange.removeProperty(PROPERTY);
        var job = exchange.getProperty("llmJob", LlmJobEnvelope.class);
        var persona = persona(text(job.metadata(), "personaId"));
        exchange.setProperty(PROPERTY, LlmExecutionContext.restore(job, persona, defaultModel, providerSchemaDefault));
    }

    public String requestKafkaKey(Exchange exchange) {
        return LlmExecutionContext.require(exchange).partitionKey();
    }

    private Persona persona(String id) {
        return id == null || id.isBlank() ? null : personas.getRequired(id);
    }

    private static String systemPrompt(LlmJobEnvelope request) {
        String system = request.systemPrompt();
        if (!request.hasResponseSchema() || (system != null && system.contains("Return valid JSON only"))) {
            return system;
        }
        String instruction = "Return valid JSON only. Do not include labels, markdown fences, comments, prefixes, or explanatory text.";
        return system == null || system.isBlank() ? instruction : system + "\n" + instruction;
    }
}
