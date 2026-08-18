package us.nusun.llmshare.framework.tests.boundary;

import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.llm.LlmAgentFactory;
import us.nusun.llmshare.framework.llm.LlmJobFactory;
import us.nusun.llmshare.framework.mcp.McpClientFactory;
import us.nusun.llmshare.framework.memory.ApproximateTokenCountEstimator;
import us.nusun.llmshare.framework.model.LlmJob;
import us.nusun.llmshare.framework.model.Persona;
import us.nusun.llmshare.framework.model.ToolProfile;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;
import us.nusun.llmshare.framework.tests.support.TestJson;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LlmJobMetadataBoundaryTest {
    @Test
    void workerExchangeRestoresFrameworkMetadataFromSerializedJob() throws Exception {
        LlmJob job = new LlmJob(
                UUID.randomUUID(),
                "low-pass",
                "prompt",
                "system",
                null,
                Map.of(
                        "agentRunId", "run-1",
                        "missionId", "mission-1",
                        "personaId", "persona-a",
                        "toolProfile", FrameworkFixtures.PROFILE_GAMMA,
                        "primitive", "act",
                        "primitiveId", "assignment-review"),
                Instant.now());
        String json = TestJson.write(job);
        LlmJob restoredJob = TestJson.read(json, LlmJob.class);
        Exchange worker = new DefaultExchange(new DefaultCamelContext());

        new LlmJobFactory(FrameworkFixtures.inferenceLaneRegistry(), FrameworkFixtures.personaCatalog())
                .restoreFrameworkMetadata(worker, restoredJob);
        worker.setProperty("llmJob", restoredJob);

        assertNull(worker.getProperty("agentRunId"));
        assertNull(worker.getProperty("missionId"));
        assertNull(worker.getProperty("personaId"));
        assertNull(worker.getProperty("toolProfile"));
        assertNull(worker.getProperty("primitive"));
        assertNull(worker.getProperty("primitiveId"));
        assertEquals("low-pass", worker.getProperty("laneId"));
        assertEquals("persona-a", restoredJob.metadata().get("personaId"));
        assertEquals(FrameworkFixtures.PROFILE_GAMMA, restoredJob.metadata().get("toolProfile"));
        assertEquals("low-pass", restoredJob.laneId());

        CapturingMcpClientFactory mcpFactory = new CapturingMcpClientFactory();
        new LlmAgentFactory.DynamicAgentFactory(
                FrameworkFixtures.inferenceLaneRegistry(),
                Map.of("low-pass", new UnusedChatModel()),
                mcpFactory,
                FrameworkFixtures.toolProfileRegistry(),
                FrameworkFixtures.personaCatalog(),
                new ApproximateTokenCountEstimator(),
                12000).createAgent(worker);

        assertEquals("persona-a", mcpFactory.persona.id());
        assertEquals(FrameworkFixtures.PROFILE_GAMMA, mcpFactory.profile.id());
    }

    @Test
    void workerExchangeRestoresPersonaLogLabelAndColorFromSerializedEnvelope() throws Exception {
        LlmJob job = new LlmJob(
                UUID.randomUUID(),
                "low-pass",
                "prompt",
                "system",
                null,
                Map.of("personaId", "persona-a"),
                Instant.now());
        Exchange worker = new DefaultExchange(new DefaultCamelContext());

        new LlmJobFactory(FrameworkFixtures.inferenceLaneRegistry(), FrameworkFixtures.personaCatalog())
                .restoreFrameworkMetadata(worker, job);

        assertEquals("PA", worker.getProperty("agentLabel"));
        assertEquals("38;2;106;169;255", worker.getProperty("agentLogColor"));
    }

    static class CapturingMcpClientFactory extends McpClientFactory {
        Persona persona;
        ToolProfile profile;

        @Override
        public List<McpClient> clientsFor(Persona persona, ToolProfile profile) {
            this.persona = persona;
            this.profile = profile;
            return List.of();
        }
    }
    private static final class UnusedChatModel implements ChatModel {
        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            throw new AssertionError("chat model should not be invoked while creating the agent");
        }
    }
}
