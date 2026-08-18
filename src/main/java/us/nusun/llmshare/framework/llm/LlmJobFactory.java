package us.nusun.llmshare.framework.llm;

import static us.nusun.llmshare.framework.model.FrameworkText.firstNonBlank;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import us.nusun.llmshare.framework.inventory.InferenceLaneRegistry;
import us.nusun.llmshare.framework.inventory.PersonaCatalog;
import us.nusun.llmshare.framework.model.InferenceLaneDefinition;
import us.nusun.llmshare.framework.model.LlmJob;
import us.nusun.llmshare.framework.model.Mission;
import us.nusun.llmshare.framework.model.PartitionKeyStrategy;
import us.nusun.llmshare.framework.model.Persona;
import us.nusun.llmshare.framework.state.AgentRun;

import java.util.UUID;

@ApplicationScoped
public class LlmJobFactory {
    @Inject InferenceLaneRegistry lanes;
    @Inject PersonaCatalog personas;

    public LlmJobFactory() {
    }

    public LlmJobFactory(InferenceLaneRegistry lanes, PersonaCatalog personas) {
        this.lanes = lanes;
        this.personas = personas;
    }

    public void prepare(Exchange exchange) {
        Object body = exchange.getMessage().getBody();
        if (!(body instanceof LlmJob job)) {
            throw new IllegalArgumentException("direct:llm requires an LlmJob body");
        }
        InferenceLaneDefinition lane = lanes.getRequired(job.laneId());
        exchange.setProperty("laneId", lane.id());
        exchange.setProperty("inferenceLane", lane);
        exchange.setProperty("modelId", selectedModel(lane, globalModelFallback(exchange)));
        exchange.setProperty("laneRequestTopic", lane.requestTopic());
        exchange.setProperty("laneReplyTopic", lane.responseTopic());
        exchange.setProperty("llmPartitionKey", partitionKey(job, exchange, lane));
        exchange.setProperty("llmJobId", job.jobId());
        exchange.setProperty("llmJob", job);
        applyLogPersona(exchange, job);
    }

    public void prepareWorkerContext(Exchange exchange) {
        prepareWorkerContext(exchange, exchange.getProperty("llmJob", LlmJob.class));
    }

    public void prepareWorkerContext(Exchange exchange, LlmJob job) {
        if (job == null) {
            return;
        }
        exchange.setProperty("llmJobId", job.jobId());
        exchange.setProperty("laneId", job.laneId());
        if (lanes != null) {
            InferenceLaneDefinition lane = lanes.getRequired(job.laneId());
            exchange.setProperty("inferenceLane", lane);
            exchange.setProperty("modelId", selectedModel(lane, globalModelFallback(exchange)));
        }
        applyLogPersona(exchange, job);
    }

    public String partitionKey(LlmJob job, Exchange exchange, InferenceLaneDefinition lane) {
        PartitionKeyStrategy strategy = lane == null ? PartitionKeyStrategy.AGENT_RUN : lane.partitionKeyStrategy();
        return switch (strategy) {
            case AGENT_RUN -> firstNonBlank(
                    agentRunId(exchange),
                    metadataValue(job, "agentRunId"),
                    missionId(exchange),
                    metadataValue(job, "missionId"),
                    String.valueOf(job.jobId()));
            case MISSION -> firstNonBlank(
                    missionId(exchange),
                    metadataValue(job, "missionId"),
                    agentRunId(exchange),
                    metadataValue(job, "agentRunId"),
                    String.valueOf(job.jobId()));
            case JOB -> String.valueOf(job.jobId());
        };
    }

    public String requestKafkaKey(Exchange exchange) {
        return exchange.getProperty("llmPartitionKey", String.class);
    }

    public UUID jobIdKafkaKey(Exchange exchange) {
        LlmJob job = exchange.getProperty("llmJob", LlmJob.class);
        return job == null ? null : job.jobId();
    }

    private void applyLogPersona(Exchange exchange, LlmJob job) {
        String personaId = firstNonBlank(
                exchange.getProperty("personaId", String.class),
                metadataValue(job, "personaId"));
        if (personaId == null || personaId.isBlank() || personas == null) {
            exchange.setProperty("agentLabel", "LLM");
            exchange.removeProperty("agentLogColor");
            return;
        }
        Persona persona = personas.getRequired(personaId);
        exchange.setProperty("agentLabel", firstNonBlank(persona.label(), persona.name(), "LLM"));
        exchange.setProperty("agentLogColor", persona.color());
    }

    private static String selectedModel(InferenceLaneDefinition lane, String globalModelFallback) {
        return LlmAgentFactory.selectedModel(lane, globalModelFallback);
    }

    private static String metadataValue(LlmJob job, String key) {
        Object value = job == null ? null : job.metadata().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String agentRunId(Exchange exchange) {
        String explicit = exchange.getProperty("agentRunId", String.class);
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        return run == null ? null : run.id();
    }

    private static String missionId(Exchange exchange) {
        String explicit = exchange.getProperty("missionId", String.class);
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        Mission mission = exchange.getProperty("mission", Mission.class);
        return mission == null ? null : mission.id();
    }

    private static String resolveOptionalPlaceholder(Exchange exchange, String placeholder) {
        try {
            return exchange.getContext().resolvePropertyPlaceholders(placeholder);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String globalModelFallback(Exchange exchange) {
        return resolveOptionalPlaceholder(exchange, "{{llm.model-default.openai.model:}}");
    }
}
