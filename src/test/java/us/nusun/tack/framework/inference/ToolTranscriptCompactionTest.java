package us.nusun.tack.framework.inference;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;
import us.nusun.tack.framework.inference.lane.ToolPassSettings;
import us.nusun.tack.framework.inference.memory.QwenTokenCountEstimator;
import us.nusun.tack.framework.inference.memory.ToolLoopGuardChatMemory.ToolPassTerminatedException;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolTranscriptCompactionTest {
    private static final QwenTokenCountEstimator ESTIMATOR = new QwenTokenCountEstimator();
    private static final List<ChatMessage> PROMPTS = List.of(SystemMessage.from("System prompt"), UserMessage.from("Inspect the project"));

    @Test
    void preservesTheNativeRequestAtAndBelowItsEffectiveBudget() {
        var result = result("read", "complete".repeat(100), false);
        var request = request(result);
        int raw = ESTIMATOR.estimateTokenCountInMessages(request.messages()) - ESTIMATOR.estimateTokenCountInMessages(PROMPTS);

        for (int budget : List.of(raw, raw * 3 / 2)) {
            var projection = project(request, budget);
            assertSame(request, projection.request());
            assertEquals(raw, projection.rawTokens());
            assertEquals(raw, projection.projectedTokens());
            assertEquals(1, projection.retainedResults());
            assertEquals(0, projection.omittedResults());
        }
    }

    @Test
    void reservesOutputAndAccountsForActualPromptsAndToolSchemas() {
        var result = result("read", "large".repeat(300), false);
        var tool = ToolSpecification.builder().name("read").description("schema".repeat(100)).build();
        var initial = request(result).toBuilder().toolSpecifications(tool).maxOutputTokens(400).build();
        var settings = new ToolPassSettings(50_000, 50, 1000, 20);
        var projection = ToolTranscriptCompaction.project(initial, settings, ESTIMATOR);

        int promptTokens = ESTIMATOR.estimateTokenCountInMessages(PROMPTS);
        int schemaTokens = ESTIMATOR.estimateTokenCountInText(tool.toJson());
        assertEquals(1000 - 400 - promptTokens - schemaTokens, projection.budget());
        assertEquals(promptTokens, projection.promptTokens());
        assertEquals(schemaTokens, projection.toolTokens());
        assertEquals(1, projection.omittedResults());
        assertEquals(0, projection.retainedResults());
        assertEquals(initial.parameters(), projection.request().parameters());
        assertTrue(ESTIMATOR.estimateTokenCountInMessages(projection.request().messages()) + schemaTokens <= 600);
    }

    @Test
    void prefersRecentResultsAmongSimilarSizesAndStopsAfterMakingHeadroom() {
        var older = result("old", "x".repeat(1800), true);
        var recent = result("recent", "y".repeat(1800), false);
        var small = result("small", "{\"state\":\"ok\"}", false);
        var initial = request(older, recent, small);
        var projection = project(initial, 850);
        var projected = projection.request().messages();

        assertEquals(1, projection.omittedResults());
        assertEquals(2, projection.retainedResults());
        assertTrue(projection.projectedTokens() <= 765);
        assertEquals(initial.messages().get(2), projected.get(2));
        var notice = assertInstanceOf(ToolExecutionResultMessage.class, projected.get(3));
        assertEquals("old", notice.id());
        assertEquals("read", notice.toolName());
        assertTrue(notice.isError());
        assertTrue(notice.text().contains("Tool returned an error."));
        assertEquals(recent, projected.get(4));
        assertEquals(small, projected.get(5));
        assertEquals(older, initial.messages().get(3));
        assertEquals(ESTIMATOR.estimateTokenCountInMessages(projected) - projection.promptTokens(),
                projection.projectedTokens());
    }

    @Test
    void targetsNinetyPercentOnlyAfterPressureAndKeepsPairingWhenHeadroomIsUnavailable() {
        var older = result("old", "x".repeat(450), false);
        var recent = result("recent", "y".repeat(450), false);
        var initial = request(older, recent);
        var raw = project(initial, 5000);
        int budget = raw.rawTokens() - 100;
        var projected = project(initial, budget);
        assertEquals(2, projected.omittedResults(), "one omission fits the hard cap but leaves less than 10% headroom");
        assertTrue(projected.projectedTokens() <= budget * 9 / 10);

        var hardFit = project(initial, projected.projectedTokens());
        assertEquals(projected.request(), hardFit.request());
    }

    @Test
    void stopsBeforeSendingWhenTheNativeRequestSkeletonCannotFit() {
        var failure = assertThrows(ToolPassTerminatedException.class, () -> project(request(), 5));
        assertTrue(failure.getMessage().contains("TACK_RUNTIME_CONTEXT_BUDGET_EXHAUSTED"));
        assertTrue(failure.getMessage().contains("Verify external state before retrying any completed operation"));
    }

    private static ChatRequest request(ToolExecutionResultMessage... results) {
        var requests = List.of(results).stream().map(result -> ToolExecutionRequest.builder()
                .id(result.id()).name("read").arguments("{}").build()).toList();
        var messages = new ArrayList<>(PROMPTS);
        messages.add(requests.isEmpty() ? AiMessage.from("x".repeat(150)) : AiMessage.from(requests));
        messages.addAll(List.of(results));
        return ChatRequest.builder().messages(messages).build();
    }

    private static ToolTranscriptCompaction.Projection project(ChatRequest request, int budget) {
        return ToolTranscriptCompaction.project(request, new ToolPassSettings(budget, 50, 30000, 1000), ESTIMATOR);
    }

    private static ToolExecutionResultMessage result(String id, String payload, boolean error) {
        return ToolExecutionResultMessage.builder().id(id).toolName("read").text(payload).isError(error).build();
    }
}
