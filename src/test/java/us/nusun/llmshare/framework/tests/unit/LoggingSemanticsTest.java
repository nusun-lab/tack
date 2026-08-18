package us.nusun.llmshare.framework.tests.unit;

import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.llm.InFlightReplyManager;
import us.nusun.llmshare.framework.llm.LlmDeadLetterFactory;
import us.nusun.llmshare.framework.llm.LlmRouteSupport;
import us.nusun.llmshare.framework.logging.LogTokens;
import us.nusun.llmshare.framework.model.LlmJob;
import us.nusun.llmshare.framework.model.LlmJobResult;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoggingSemanticsTest {
    @Test
    void personaPromptUsesDimColorAndModelResponseUsesRegularColor() {
        CapturingLogTokens logs = new CapturingLogTokens();
        LlmRouteSupport support = new LlmRouteSupport(
                new DefaultCamelContext(),
                FrameworkFixtures.inferenceLaneRegistry(),
                new InFlightReplyManager(),
                new LlmDeadLetterFactory(),
                logs);
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.setProperty("agentLabel", "DEV");
        exchange.setProperty("agentLogColor", "38;2;100;50;10");
        LlmJob job = job();
        exchange.setProperty("llmJob", job);

        support.logPrompt(job, exchange);
        support.logModelResponse(LlmJobResult.succeeded(job, "model", "{\"ok\":true}", Instant.now()), exchange);

        assertEquals(2, logs.messages.size());
        assertTrue(logs.messages.get(0).startsWith("\u001B[38;2;55;28;6m[DEV] SYSTEM system\nPROMPT prompt"));
        assertTrue(logs.messages.get(1).startsWith("\u001B[38;2;100;50;10m[DEV] RESPONSE {\"ok\":true}"));
    }

    @Test
    void nonAnsiLoggingStillUsesPersonaLabel() {
        LogTokens logs = new LogTokens(false);

        assertEquals("[DEV] SYSTEM system\nPROMPT text", logs.wrapDim("DEV", "38;2;100;50;10", "SYSTEM system\nPROMPT text"));
        assertEquals("[DEV] RESPONSE text", logs.wrap("DEV", "38;2;100;50;10", "RESPONSE text"));
    }

    private static LlmJob job() {
        return new LlmJob(
                java.util.UUID.randomUUID(),
                "low-pass",
                "prompt",
                "system",
                null,
                Map.of(),
                java.time.Instant.now());
    }

    private static final class CapturingLogTokens extends LogTokens {
        private final List<String> messages = new ArrayList<>();

        private CapturingLogTokens() {
            super(true);
        }

        @Override
        public void print(String message) {
            messages.add(message);
        }
    }
}
