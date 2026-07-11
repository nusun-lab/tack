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
import us.nusun.llmshare.framework.model.ToolProfile;
import us.nusun.llmshare.framework.model.LlmJobEnvelope;
import us.nusun.llmshare.framework.model.LlmJobRequest;
import us.nusun.llmshare.framework.model.LlmPassType;
import us.nusun.llmshare.framework.model.Persona;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;
import us.nusun.llmshare.framework.tests.support.TestJson;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmJobMetadataBoundaryTest {
    @Test
    void workerExchangeRestoresFrameworkMetadataFromSerializedEnvelope() throws Exception {
        LlmJobRequest request = new LlmJobRequest(
                null,
                "low-pass",
                LlmPassType.TOOL,
                "act:assignment-review",
                "Persona A",
                0,
                "prompt",
                "system",
                null,
                Map.of(
                        "agentRunId", "run-1",
                        "missionId", "mission-1",
                        "personaId", "persona-a",
                        "toolProfile", FrameworkFixtures.PROFILE_GAMMA,
                        "primitive", "act",
                        "primitiveId", "assignment-review",
                        "laneId", "low-pass",
                        "passType", "TOOL"),
                null).normalized();
        LlmJobEnvelope envelope = LlmJobEnvelope.queued(request);
        String json = TestJson.write(envelope);
        LlmJobEnvelope restoredEnvelope = TestJson.read(json, LlmJobEnvelope.class);
        Exchange worker = new DefaultExchange(new DefaultCamelContext());

        new LlmJobFactory().restoreFrameworkMetadata(worker, restoredEnvelope);

        assertEquals("run-1", worker.getProperty("agentRunId"));
        assertEquals("mission-1", worker.getProperty("missionId"));
        assertEquals("persona-a", worker.getProperty("personaId"));
        assertEquals(FrameworkFixtures.PROFILE_GAMMA, worker.getProperty("toolProfile"));
        assertEquals("act", worker.getProperty("primitive"));
        assertEquals("assignment-review", worker.getProperty("primitiveId"));
        assertEquals("low-pass", worker.getProperty("laneId"));
        assertEquals(LlmPassType.TOOL, worker.getProperty("passType"));
        assertEquals("persona-a", restoredEnvelope.metadata().get("personaId"));
        assertEquals(FrameworkFixtures.PROFILE_GAMMA, restoredEnvelope.metadata().get("toolProfile"));
        assertEquals("low-pass", restoredEnvelope.laneId());
        assertEquals(LlmPassType.TOOL, restoredEnvelope.passType());

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
        LlmJobRequest request = new LlmJobRequest(
                null,
                "low-pass",
                LlmPassType.SYNTHESIS,
                "framework",
                "Persona A",
                0,
                "prompt",
                "system",
                null,
                Map.of("personaId", "persona-a"),
                null).normalized();
        LlmJobEnvelope envelope = LlmJobEnvelope.queued(request);
        Exchange worker = new DefaultExchange(new DefaultCamelContext());

        new LlmJobFactory(FrameworkFixtures.inferenceLaneRegistry(), FrameworkFixtures.personaCatalog())
                .restoreFrameworkMetadata(worker, envelope);

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
