package us.nusun.llmshare.framework.tests.boundary;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.mcp.client.McpClient;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.llm.LlmJobFactory;
import us.nusun.llmshare.framework.mcp.McpClientFactory;
import us.nusun.llmshare.framework.model.ToolProfile;
import us.nusun.llmshare.framework.model.LlmJobEnvelope;
import us.nusun.llmshare.framework.model.LlmJobRequest;
import us.nusun.llmshare.framework.model.Persona;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class LlmJobMetadataBoundaryTest {
    @Test
    void workerExchangeRestoresFrameworkMetadataFromSerializedEnvelope() throws Exception {
        LlmJobRequest request = new LlmJobRequest(
                null,
                "tool-low",
                "Persona A",
                0,
                "prompt",
                "system",
                null,
                Map.of(
                        "agentRunId", "run-1",
                        "missionId", "mission-1",
                        "personaId", "persona-a",
                        "toolProfile", "source-control",
                        "primitive", "act",
                        "primitiveId", "assignment-review",
                        "executionRuntime", "tool-low",
                        "inferenceLane", "low-pass"),
                null).normalized();
        LlmJobEnvelope envelope = LlmJobEnvelope.queued(request);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String json = mapper.writeValueAsString(envelope);
        LlmJobEnvelope restoredEnvelope = mapper.readValue(json, LlmJobEnvelope.class);
        Exchange worker = new DefaultExchange(new DefaultCamelContext());

        new LlmJobFactory().restoreFrameworkMetadata(worker, restoredEnvelope);

        assertEquals("run-1", worker.getProperty("agentRunId"));
        assertEquals("mission-1", worker.getProperty("missionId"));
        assertEquals("persona-a", worker.getProperty("personaId"));
        assertEquals("source-control", worker.getProperty("toolProfile"));
        assertEquals("act", worker.getProperty("primitive"));
        assertEquals("assignment-review", worker.getProperty("primitiveId"));
        assertEquals("tool-low", worker.getProperty("executionRuntimeId"));
        assertEquals("low-pass", worker.getProperty("inferenceLane"));
        assertEquals("persona-a", restoredEnvelope.metadata().get("personaId"));
        assertEquals("source-control", restoredEnvelope.metadata().get("toolProfile"));

        CapturingMcpClientFactory mcpFactory = new CapturingMcpClientFactory();
        Class<?> factoryType = Class.forName("us.nusun.llmshare.framework.llm.LlmAgentFactory$ExecutionRuntimeAgentFactory");
        Constructor<?> constructor = factoryType.getDeclaredConstructor(
                us.nusun.llmshare.framework.inventory.ExecutionRuntimeRegistry.class,
                Map.class,
                McpClientFactory.class,
                us.nusun.llmshare.framework.inventory.ToolProfileRegistry.class,
                us.nusun.llmshare.framework.inventory.PersonaCatalog.class,
                dev.langchain4j.model.TokenCountEstimator.class,
                int.class);
        constructor.setAccessible(true);
        Object factory = constructor.newInstance(
                FrameworkFixtures.executionRuntimeRegistry(),
                Map.of(),
                mcpFactory,
                FrameworkFixtures.toolProfileRegistry(),
                FrameworkFixtures.personaCatalog(),
                null,
                12000);
        Method mcpClients = factoryType.getDeclaredMethod("mcpClients", Exchange.class);
        mcpClients.setAccessible(true);
        mcpClients.invoke(factory, worker);

        assertEquals("persona-a", mcpFactory.persona.id());
        assertEquals("source-control", mcpFactory.profile.id());
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
}
