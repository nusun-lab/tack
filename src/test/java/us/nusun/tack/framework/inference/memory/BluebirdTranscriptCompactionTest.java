package us.nusun.tack.framework.inference.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.apache.camel.component.langchain4j.agent.api.AiAgentBody;
import org.jboss.logmanager.ExtLogRecord;
import org.jboss.logmanager.LogContext;
import org.junit.jupiter.api.Test;
import us.nusun.tack.framework.inference.LlmPassType;
import us.nusun.tack.framework.tests.support.FrameworkFixtures;
import us.nusun.tack.framework.tool.mcp.McpClientFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BluebirdTranscriptCompactionTest {
    private static final QwenTokenCountEstimator ESTIMATOR = new QwenTokenCountEstimator();
    private static final String AVERY_ID = "cP6gs9qMzoK6S5osFIatnqAE1eOH9Wqp";
    private static final String MILES_ID = "MHdMpY8I8tQMml3BxMc7XY8I9YPq8pUu";

    @Test
    void pressureFromATaskListPreservesCompleteWorkspaceEvidenceInTheOutboundRequest() throws Exception {
        // Complete PM membership and column responses from Bluebird, with member email addresses anonymized.
        String members = fixture("list_workspace_members");
        String columns = fixture("list_project_columns");
        // The log only contains a preview of the large responses. These are synthetic, valid JSON payloads.
        String tasks = "{\"tasks\":[{\"description\":\"" + "a".repeat(132_000) + "\"}]}";
        String channel = "{\"members\":[{\"notes\":\"" + "b".repeat(21_000) + "\"}]}";
        var requestGroups = List.of(
                List.of(call("members", "list_workspace_members"), call("columns", "list_project_columns")),
                List.of(call("tasks", "list_tasks")),
                List.of(call("channel", "get_channel_members")));
        var requests = new ArrayList<ChatRequest>();
        var payloads = Map.of("list_workspace_members", members, "list_project_columns", columns,
                "list_tasks", tasks, "get_channel_members", channel);
        var budgets = new ArrayList<String>();
        var logger = LogContext.getLogContext().getLogger("us.nusun.tack.framework.inference.LlmCallDiagnostics");
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record instanceof ExtLogRecord log && log.getFormattedMessage().contains("TOKEN BUDGET")) {
                    budgets.add(log.getFormattedMessage());
                }
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(handler);
        try {
            run(requests, requestGroups, 50_000, 50, tools(List.copyOf(payloads.keySet()), payloads::get));
        } finally {
            logger.removeHandler(handler);
        }

        assertEquals(4, requests.size());
        ChatRequest projected = requests.getLast();
        var results = results(projected);
        String modelTranscript = projected.messages().toString();
        System.out.printf("Bluebird outbound estimates before pressure=%d after=%d structuredResults=%d Avery=%s Miles=%s%n",
                ESTIMATOR.estimateTokenCountInMessages(requests.get(2).messages()),
                ESTIMATOR.estimateTokenCountInMessages(projected.messages()), results.size(),
                modelTranscript.contains(AVERY_ID), modelTranscript.contains(MILES_ID));
        assertTrue(modelTranscript.contains(AVERY_ID) && modelTranscript.contains(MILES_ID),
                "both verified engineer IDs must reach the model");
        assertEquals(List.of("members", "columns", "tasks", "channel"),
                results.stream().map(ToolExecutionResultMessage::id).toList());
        assertEquals(members, results.get(0).text());
        assertEquals(columns, results.get(1).text());
        assertTrue(results.get(2).text().contains("Output omitted"));
        assertEquals(channel, results.get(3).text());
        assertEquals(tasks, results(requests.get(2)).getLast().text());
        assertEquals(requestGroups, projected.messages().stream().filter(AiMessage.class::isInstance)
                .map(AiMessage.class::cast).map(AiMessage::toolExecutionRequests).toList());
        assertInstanceOf(SystemMessage.class, projected.messages().getFirst());
        assertEquals(1, projected.messages().stream().filter(SystemMessage.class::isInstance).count());

        int promptTokens = ESTIMATOR.estimateTokenCountInMessages(requests.getFirst().messages());
        int projectedTokens = ESTIMATOR.estimateTokenCountInMessages(projected.messages()) - promptTokens;
        int reclaimed = ESTIMATOR.estimateTokenCountInMessage(results(requests.get(2)).getLast())
                - ESTIMATOR.estimateTokenCountInMessage(results.get(2));
        assertEquals(requests.size(), budgets.size());
        assertTrue(budgets.get(2).contains("omittedResults=0"));
        assertTrue(budgets.getLast().contains("transcriptRaw=" + (projectedTokens + reclaimed)
                + " transcriptProjected=" + projectedTokens + " transcriptBudget=50000"));
        assertTrue(budgets.getLast().contains("retainedResults=3 omittedResults=1 reclaimedTokens=" + reclaimed + " compacted=true"));
    }

    @Test
    void successfulWriteIsNeverReplayedWhenItsResultIsOmitted() {
        var executions = new ArrayList<String>();
        var requests = new ArrayList<ChatRequest>();
        var groups = List.of(List.of(call("write", "publish")), List.of(call("read", "inspect")));
        run(requests, groups, 500, 8, tools(List.of("publish", "inspect"), name -> {
            executions.add(name);
            return name.equals("publish") ? "write-result".repeat(1000) : "{\"published\":true}";
        }));

        assertEquals(List.of("publish", "inspect"), executions);
        var firstNotice = results(requests.get(1)).getFirst();
        var laterNotice = results(requests.getLast()).getFirst();
        assertEquals(firstNotice, laterNotice);
        assertEquals("write", laterNotice.id());
        assertEquals("publish", laterNotice.toolName());
        assertEquals(Boolean.FALSE, laterNotice.isError());
        assertTrue(laterNotice.text().contains("Tool completed without a reported error."));
        assertTrue(laterNotice.text().contains("Do not repeat the call just to recover its output."));
        assertEquals("{\"published\":true}", results(requests.getLast()).getLast().text());
    }

    @Test
    void repeatedToolGuardUsesOriginalResultsEvenWhenTheModelSeesOnlyNotices() {
        var groups = List.of(List.of(call("1", "inspect")), List.of(call("2", "inspect")), List.of(call("3", "inspect")));
        var identical = new ArrayList<ChatRequest>();
        var completed = new ArrayList<String>();
        assertThrows(ToolLoopGuardChatMemory.RepeatedToolExecutionException.class,
                () -> run(identical, groups, 500, 8, tools(List.of("inspect"), name -> {
                    completed.add(name);
                    return "same".repeat(1000);
                })));
        assertEquals(2, completed.size());
        assertTrue(results(identical.getLast()).stream().allMatch(result -> result.text().contains("Output omitted")));

        completed.clear();
        var different = new ArrayList<ChatRequest>();
        run(different, groups, 500, 8, tools(List.of("inspect"), name -> {
            completed.add(name);
            return ("result-" + completed.size()).repeat(1000);
        }));
        assertEquals(3, completed.size());
        assertTrue(results(different.getLast()).stream().allMatch(result -> result.text().contains("Output omitted")));
    }

    @Test
    void omittedResultsStillCountAgainstTheIndividualToolBudget() {
        var requests = new ArrayList<ChatRequest>();
        var completed = new ArrayList<String>();
        var groups = List.of(List.of(call("1", "inspect")), List.of(call("2", "inspect"), call("3", "inspect")));
        assertThrows(ToolLoopGuardChatMemory.ToolCallBudgetExceededException.class,
                () -> run(requests, groups, 500, 2, tools(List.of("inspect"), name -> {
                    completed.add(name);
                    return "large".repeat(1000);
                })));
        assertEquals(1, completed.size());
        assertTrue(results(requests.getLast()).getFirst().text().contains("Output omitted"));
    }

    private static void run(List<ChatRequest> requests, List<List<ToolExecutionRequest>> requestGroups,
                            int memoryTokens, int maxCalls, ToolProvider provider) {
        ChatModel model = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                int round = requests.size();
                requests.add(request);
                return ChatResponse.builder().aiMessage(round < requestGroups.size()
                        ? AiMessage.from(requestGroups.get(round))
                        : AiMessage.from("Observations complete.")).build();
            }
        };
        var factory = FrameworkFixtures.agentFactory(
                "high-pass", model, new McpClientFactory(), ESTIMATOR, memoryTokens, maxCalls, null);
        var agent = factory.createAgent(FrameworkFixtures.agentExchange("high-pass", LlmPassType.ACTION));
        agent.chat(new AiAgentBody<>("Inspect the mission workspace, board and channel.", "You are a tester.", "bluebird"), provider);
    }

    private static List<ToolExecutionResultMessage> results(ChatRequest request) {
        return request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast).toList();
    }

    private static String fixture(String name) throws Exception {
        try (var stream = BluebirdTranscriptCompactionTest.class.getResourceAsStream("/inference/bluebird/" + name + ".json")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
    }

    private static ToolExecutionRequest call(String id, String toolName) {
        return ToolExecutionRequest.builder().id(id).name(toolName).arguments("{}").build();
    }

    private static ToolProvider tools(List<String> names, Function<String, String> payload) {
        return request -> {
            var builder = ToolProviderResult.builder();
            names.forEach(name -> builder.add(
                    ToolSpecification.builder().name(name).description("Read recorded external state").build(),
                    (toolRequest, memoryId) -> payload.apply(name)));
            return builder.build();
        };
    }
}
