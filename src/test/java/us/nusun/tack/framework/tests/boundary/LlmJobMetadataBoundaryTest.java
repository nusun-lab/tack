package us.nusun.tack.framework.tests.boundary;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import us.nusun.tack.framework.llm.LlmExecutionContext;
import us.nusun.tack.framework.mcp.McpClientFactory;
import us.nusun.tack.framework.tests.support.TestTokenCountEstimator;
import us.nusun.tack.framework.model.ToolProfile;
import us.nusun.tack.framework.model.LlmJobEnvelope;
import us.nusun.tack.framework.model.LlmPassType;
import us.nusun.tack.framework.model.Persona;
import us.nusun.tack.framework.tests.support.FrameworkFixtures;
import us.nusun.tack.framework.tests.support.TestJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LlmJobMetadataBoundaryTest {
    @Test
    void workerExchangeRestoresFrameworkMetadataFromSerializedEnvelope() throws Exception {
        var retry = new ArrayList<>(List.of(1, true));
        LlmJobEnvelope request = new LlmJobEnvelope(
                null,
                "low-pass",
                LlmPassType.ACTION,
                "act:assignment-review",
                "Persona A",
                0,
                "prompt",
                "system",
                null,
                Map.ofEntries(
                        Map.entry("agentRunId", "run-1"),
                        Map.entry("missionId", "mission-1"),
                        Map.entry("personaId", "persona-a"),
                        Map.entry("toolProfile", FrameworkFixtures.PROFILE_GAMMA),
                        Map.entry("primitive", "act"),
                        Map.entry("primitiveId", "assignment-review"),
                        Map.entry("laneId", "low-pass"),
                        Map.entry("passType", "ACTION"),
                        Map.entry("agentRunCapacityMaximum", 5),
                        Map.entry("llmForceProviderSchema", true),
                        Map.entry("extension", Map.of("retry", retry))),
                null);
        var producer = new DefaultExchange(new DefaultCamelContext());
        producer.getMessage().setBody(request);
        FrameworkFixtures.jobFactory().prepare(producer);
        LlmJobEnvelope envelope = producer.getMessage().getBody(LlmJobEnvelope.class);
        retry.clear();
        assertEquals(Map.of("retry", List.of(1, true)), envelope.metadata().get("extension"));
        LlmJobEnvelope restoredEnvelope = TestJson.read(TestJson.write(envelope), LlmJobEnvelope.class);
        Exchange worker = new DefaultExchange(producer.getContext());
        FrameworkFixtures.restoreJob(worker, restoredEnvelope);
        assertEquals(LlmExecutionContext.on(producer), LlmExecutionContext.on(worker));

        var invocation = LlmExecutionContext.require(worker);
        assertEquals("run-1", invocation.agentRunId());
        assertEquals("mission-1", invocation.missionId());
        assertEquals("persona-a", invocation.personaId());
        assertEquals(FrameworkFixtures.PROFILE_GAMMA, invocation.toolProfile());
        assertEquals("act", invocation.primitive());
        assertEquals("assignment-review", invocation.primitiveId());
        assertEquals("low-pass", invocation.laneId());
        assertEquals(LlmPassType.ACTION, invocation.passType());
        assertNull(worker.getProperty("agentRunId"));
        assertNull(worker.getProperty("passType"));
        assertEquals("persona-a", restoredEnvelope.metadata().get("personaId"));
        assertEquals(FrameworkFixtures.PROFILE_GAMMA, restoredEnvelope.metadata().get("toolProfile"));
        assertEquals("low-pass", restoredEnvelope.laneId());
        assertEquals(LlmPassType.ACTION, restoredEnvelope.passType());
        LlmExecutionContext context = LlmExecutionContext.on(worker);
        assertEquals(restoredEnvelope.jobId(), context.jobId());
        assertEquals(5, context.capacityMaximum());
        assertEquals(true, context.providerSchemaEnabled());
        assertEquals(Map.of("retry", List.of(1, true)), restoredEnvelope.metadata().get("extension"));
        assertNull(worker.getProperty("extension"));
        var child = worker.copy();
        child.removeProperty(LlmExecutionContext.PROPERTY);
        assertEquals(context, LlmExecutionContext.on(worker));

        CapturingMcpClientFactory mcpFactory = new CapturingMcpClientFactory();
        FrameworkFixtures.agentFactory(
                "low-pass", new UnusedChatModel(), mcpFactory, new TestTokenCountEstimator(), 12000, 4, null).createAgent(worker);

        assertEquals("persona-a", mcpFactory.persona.id());
        assertEquals(FrameworkFixtures.PROFILE_GAMMA, mcpFactory.profile.id());
    }

    @Test
    void workerExchangeRestoresPersonaLogLabelAndColorFromSerializedEnvelope() throws Exception {
        LlmJobEnvelope request = new LlmJobEnvelope(
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
                null);
        LlmJobEnvelope envelope = FrameworkFixtures.queuedJob(request);
        Exchange worker = new DefaultExchange(new DefaultCamelContext());

        FrameworkFixtures.restoreJob(worker, envelope);

        assertEquals("PA", LlmExecutionContext.require(worker).logLabel());
        assertEquals("38;2;106;169;255", LlmExecutionContext.require(worker).logColor());
    }

    static class CapturingMcpClientFactory extends McpClientFactory {
        Persona persona;
        ToolProfile profile;

        @Override
        public List<dev.langchain4j.service.tool.ToolProvider> toolProvidersFor(Persona persona, ToolProfile profile) {
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
