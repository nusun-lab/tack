package us.nusun.tack.framework.tests.unit;

import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import us.nusun.tack.framework.llm.InFlightReplyManager;
import us.nusun.tack.framework.llm.LlmDeadLetterFactory;
import us.nusun.tack.framework.llm.LlmRouteSupport;
import us.nusun.tack.framework.logging.LogTokens;
import us.nusun.tack.framework.model.LlmJobEnvelope;
import us.nusun.tack.framework.model.LlmJobRequest;
import us.nusun.tack.framework.model.LlmJobResult;
import us.nusun.tack.framework.model.LlmPassType;
import us.nusun.tack.framework.tests.support.FrameworkFixtures;

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
        LlmJobEnvelope job = job();
        exchange.setProperty("llmJob", job);

        support.logPrompt(job, exchange);
        support.logModelResponse(LlmJobResult.succeeded(job, "model", "{\"ok\":true}", "{\"ok\":true}", Instant.now()), exchange);

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

    private static LlmJobEnvelope job() {
        return LlmJobEnvelope.queued(new LlmJobRequest(
                null,
                "low-pass",
                LlmPassType.SYNTHESIS,
                "test",
                "test",
                0,
                "prompt",
                "system",
                null,
                Map.of(),
                null).normalized());
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
