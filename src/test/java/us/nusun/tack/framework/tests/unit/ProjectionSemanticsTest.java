package us.nusun.tack.framework.tests.unit;

import org.apache.camel.Exchange;
import org.junit.jupiter.api.Test;
import us.nusun.tack.framework.inventory.RoutineCatalog;
import us.nusun.tack.framework.model.AgentRunProjection;
import us.nusun.tack.framework.model.CreateResult;
import us.nusun.tack.framework.model.DecisionResult;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import us.nusun.tack.framework.model.LlmJobEnvelope;
import us.nusun.tack.framework.model.LlmPassType;
import us.nusun.tack.framework.model.RoutineDefinition;
import us.nusun.tack.framework.model.StepResult;
import us.nusun.tack.framework.model.ThoughtFrame;
import us.nusun.tack.framework.projection.AgentRunProjectionManager;
import us.nusun.tack.framework.projection.ProjectionStore;
import us.nusun.tack.framework.routine.ThoughtStackSupport;
import us.nusun.tack.framework.state.AgentRun;
import us.nusun.tack.framework.state.ThoughtStack;
import us.nusun.tack.framework.tests.support.FrameworkFixtures;
import us.nusun.tack.framework.tests.support.TestJson;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectionSemanticsTest {
    @Test
    void projectsCompletedResultsInOrderAndPreservesLatestAct() {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);

        var act = new StepResult("inspect", "Current thought", "Inspected.", "SUCCEEDED", false, 1);
        run.apply(act);
        manager.record(exchange, "ACT_COMPLETED");
        assertEquals("step", store.get(run.id()).latestResult().get("type"));

        run.apply(new DecisionResult("ready", "yes", 1, "Evidence is sufficient"));
        manager.record(exchange, "DECISION_COMPLETED");
        assertEquals("decision", store.get(run.id()).latestResult().get("type"));

        run.apply(new CreateResult("report", "report-schema", JsonNodeFactory.instance.objectNode()));
        manager.record(exchange, "CREATE_COMPLETED");
        assertEquals("create", store.get(run.id()).latestResult().get("type"));
        assertEquals(act, run.latestStepResult());
    }

    @Test
    void concurrentUpdatesRetainThoughtEventsAndBoundTheTimeline() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        manager.record(exchange, "AGENT_RUN_STARTED");

        int events = 40;
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int index = 0; index < events; index++) {
                Exchange copy = exchange.copy();
                int sequence = index;
                tasks.add(executor.submit(() -> manager.record(copy, "THOUGHT_REWRITTEN", Map.of("sequence", sequence))));
            }
            for (var task : tasks) {
                task.get();
            }
        }

        AgentRunProjection projection = store.get(run.id());
        assertEquals(10, projection.timeline().size());
        assertEquals(events, projection.thoughtHistory().stream().map(event -> event.get("sequence")).distinct().count());
    }

    @Test
    void projectionBuildsDashboardSafeSnapshotFromLiveExchangeState() {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        run.currentPrimitive("act");
        run.currentId("work-item-review");
        run.apply(new StepResult("work-item-review", null, "Project membership is missing.", "SUCCEEDED", false, 0));
        exchange.setProperty("toolProfile", FrameworkFixtures.PROFILE_BETA);
        String fullPrompt = "Review one current item in the project management system. " + "Preserve this sentence. ".repeat(30);
        exchange.setProperty("actPrompt", fullPrompt);
        exchange.setProperty("resolvedLane", "high-pass");
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("pm", "pm-cycle", "Project Manager routine context", 900, "Current state: ".repeat(120)));
        exchange.setProperty(ThoughtStackSupport.STACK_PROPERTY, stack);

        manager.record(exchange, "ACT_COMPLETED");

        AgentRunProjection projection = store.get(run.id());
        assertNotNull(projection);
        assertEquals(run.id(), projection.agentRunId());
        assertEquals(AgentRun.STATUS_RUNNING, projection.status());
        assertEquals("mission-1", projection.mission().get("id"));
        assertEquals("Test mission", projection.mission().get("goal"));
        assertEquals("Test run input", ((Map<?, ?>) projection.input()).get("brief"));
        assertEquals(FrameworkFixtures.PERSONA_A, projection.persona().get("id"));
        assertEquals(FrameworkFixtures.ROUTINE_A, projection.routine().get("id"));
        assertTrue(projection.current().isEmpty());
        assertEquals(FrameworkFixtures.PROFILE_BETA, projection.toolProfile());
        assertEquals("high-pass", projection.laneId());
        assertEquals("step", projection.latestResult().get("type"));
        assertEquals("Project membership is missing.", projection.latestResult().get("summary"));
        assertEquals("ACT_COMPLETED", projection.timeline().getFirst().get("type"));
        assertTrue(projection.thoughtHistory().isEmpty());
        assertEquals(Boolean.TRUE, projection.thoughtStack().getFirst().get("truncated"));
        assertEquals("pm-cycle", projection.thoughtStack().getFirst().get("displayId"));
    }


    @Test
    void llmStartProjectionAdvancesOnlyFromQueuedJobMetadataAtExecutionBoundary() {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        run.currentPrimitive("decide");
        run.currentId("previous-step");
        exchange.setProperty("decisionPrompt", "Previous prompt");
        manager.record(exchange, "DECISION_COMPLETED");
        run.currentPrimitive("act");
        run.currentId("inspect-current-work");

        LlmJobEnvelope job = LlmJobEnvelope.queued(new LlmJobEnvelope(
                null,
                "high-pass",
                LlmPassType.ACTION,
                "act:inspect-current-work",
                "Persona A",
                0,
                "Rendered model prompt",
                "System prompt",
                null,
                Map.of(
                        "agentRunId", run.id(),
                        "primitive", "act",
                        "primitiveId", "inspect-current-work",
                        "primitivePrompt", "Inspect the current work item.",
                        "toolProfile", FrameworkFixtures.PROFILE_BETA),
                null).normalized());
        exchange.setProperty("agentRunId", run.id());
        exchange.setProperty("llmJob", job);

        manager.recordLlmStarted(exchange);

        AgentRunProjection projection = store.get(run.id());
        assertEquals("act", projection.current().get("primitive"));
        assertEquals("inspect-current-work", projection.current().get("id"));
        assertEquals(job.jobId().toString(), projection.current().get("jobId"));
        assertEquals("Inspect the current work item.", projection.current().get("request"));
        assertEquals("", projection.current().get("response"));
        assertEquals("high-pass", projection.laneId());
        assertEquals(FrameworkFixtures.PROFILE_BETA, projection.toolProfile());
        assertEquals("ACT_STARTED", projection.timeline().getLast().get("type"));

        run.currentPrimitive("thought-enter");
        run.currentId("temporary-thought");
        manager.record(exchange, "THOUGHT_ENTERED");
        projection = store.get(run.id());
        assertEquals("act", projection.current().get("primitive"));
        assertEquals("inspect-current-work", projection.current().get("id"));
        assertEquals("Inspect the current work item.", projection.current().get("request"));
        assertEquals("", projection.current().get("response"));

        manager.recordPrimitiveCompleted(exchange, "act", "inspect-current-work", "Current response");
        projection = store.get(run.id());
        assertEquals("Current response", projection.current().get("response"));

        manager.record(exchange, "TOOL_PASS_COMPLETED");
        projection = store.get(run.id());
        assertTrue(projection.timeline().stream().anyMatch(event -> "ACT_STARTED".equals(event.get("type"))));
        assertEquals("TOOL_PASS_COMPLETED", projection.timeline().getLast().get("type"));
        assertEquals("Current response", projection.current().get("response"));
    }

    @Test
    void internalSynthesisDoesNotBlankCompletedActProjection() {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        manager.record(exchange, "AGENT_RUN_STARTED");

        LlmJobEnvelope action = LlmJobEnvelope.queued(new LlmJobEnvelope(
                null,
                "high-pass",
                LlmPassType.ACTION,
                "act:inspect",
                "Persona A",
                0,
                "Rendered action prompt",
                "System prompt",
                null,
                Map.of(
                        "agentRunId", run.id(),
                        "primitive", "act",
                        "primitiveId", "inspect",
                        "primitivePrompt", "Inspect current work."),
                null).normalized());
        exchange.setProperty("agentRunId", run.id());
        exchange.setProperty("llmJob", action);
        manager.recordLlmStarted(exchange);
        manager.recordPrimitiveCompleted(exchange, "act", "inspect", "Inspection complete.");

        LlmJobEnvelope synthesis = LlmJobEnvelope.queued(new LlmJobEnvelope(
                null,
                "low-pass",
                LlmPassType.SYNTHESIS,
                "act:inspect",
                "Persona A",
                0,
                "Internal synthesis prompt",
                "System prompt",
                null,
                Map.of(
                        "agentRunId", run.id(),
                        "primitive", "act",
                        "primitiveId", "inspect",
                        "primitivePrompt", "Inspect current work."),
                null).normalized());
        exchange.setProperty("llmJob", synthesis);
        manager.recordLlmStarted(exchange);

        AgentRunProjection projection = store.get(run.id());
        assertEquals("Inspect current work.", projection.current().get("request"));
        assertEquals("Inspection complete.", projection.current().get("response"));
    }

    @Test
    void projectionIncludesSelectedSubroutineMetadata() {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        RoutineCatalog routines = new RoutineCatalog(List.of(RoutineDefinition.builder(FrameworkFixtures.ROUTINE_A)
                .name("Routine A")
                .description("Synthetic routine")
                .routeUri("direct:routine-a")
                .subroutine("phase-a", "Perform phase A.", "direct:routine-a-phase-a")
                .build()));
        FrameworkFixtures.set(manager, "routines", routines);
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        AgentRun run = new AgentRun(
                "run-focused",
                "mission-1",
                null,
                FrameworkFixtures.PERSONA_A,
                FrameworkFixtures.ROUTINE_A,
                "phase-a",
                Map.of("input", Map.of("focus", "phase A only")));
        exchange.setProperty("agentRun", run);

        manager.record(exchange, "AGENT_RUN_ACCEPTED");

        AgentRunProjection projection = store.get("run-focused");
        assertEquals("phase-a", projection.subroutine().get("id"));
        assertEquals("Perform phase A.", projection.subroutine().get("description"));
        assertEquals("direct:routine-a-phase-a", projection.subroutine().get("routeUri"));
        assertEquals("phase A only", ((Map<?, ?>) projection.input()).get("focus"));
    }

    @Test
    void projectionStoreReturnsMostRecentlyUpdatedProjectionFirst() throws InterruptedException {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        Exchange first = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        first.setProperty("agentRun", new AgentRun("run-first", "mission-1", FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A, Map.of()));
        Thread.sleep(2);
        Exchange second = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        second.setProperty("agentRun", new AgentRun("run-second", "mission-1", FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A, Map.of()));
        second.getProperty("agentRun", AgentRun.class).currentId("second");

        manager.record(first, "FIRST");
        Thread.sleep(2);
        manager.record(second, "SECOND");

        assertEquals(second.getProperty("agentRun", AgentRun.class).id(), store.all().getFirst().agentRunId());
    }

    @Test
    void projectionTimelineKeepsOnlyTheTenMostRecentEvents() {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);

        for (int i = 0; i < 12; i++) {
            manager.record(exchange, "EVENT_" + i);
        }

        AgentRunProjection projection = store.get(run.id());
        assertNotNull(projection);
        assertEquals(10, projection.timeline().size());
        assertEquals("EVENT_2", projection.timeline().getFirst().get("type"));
        assertEquals("EVENT_11", projection.timeline().getLast().get("type"));
    }

    @Test
    void thoughtLifecycleEventsRemainInspectableAfterActiveStackChanges() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        ThoughtStackSupport thoughts = new ThoughtStackSupport();
        FrameworkFixtures.set(thoughts, "projections", manager);
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);

        ThoughtFrame frame = thoughts.push(exchange, "pm-cycle", "PM cycle.", 300);
        exchange.getProperty(ThoughtStackSupport.STACK_PROPERTY, ThoughtStack.class)
                .rewrite(frame.id(), "durable child content");
        manager.record(exchange, "THOUGHT_REWRITTEN", Map.of(
                "thoughtName", frame.name(),
                "thoughtId", frame.id(),
                "content", "durable child content",
                "primitive", "act",
                "timestamp", java.time.Instant.now().toString()));
        thoughts.pop().process(exchange);

        AgentRunProjection projection = store.get(run.id());
        assertTrue(projection.thoughtStack().isEmpty());
        assertTrue(projection.thoughtHistory().stream().anyMatch(row -> "THOUGHT_REWRITTEN".equals(row.get("type"))
                && "durable child content".equals(row.get("content"))));
        assertTrue(projection.thoughtHistory().stream().anyMatch(row -> "THOUGHT_EXITED".equals(row.get("type"))
                && "durable child content".equals(row.get("content"))));
    }

    @Test
    void projectionIncludesFullThoughtContentButOmitsSecretsAndRawToolPayloads() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        String fullThought = "Complete delivery thought content. ".repeat(100);
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("pm", "Project Manager routine context", 900, fullThought));
        exchange.setProperty(ThoughtStackSupport.STACK_PROPERTY, stack);
        exchange.setProperty("authorization", "Bearer SECRET_TOKEN");
        exchange.setProperty("mcpHeaders", Map.of("Authorization", "Bearer SECRET_TOKEN"));
        exchange.setProperty("toolPassResult", "RAW_TOOL_PAYLOAD_" + "x".repeat(5000) + "_SECRET_TOKEN");
        run.currentPrimitive("act");
        run.currentId("inspect-delivery-state");
        run.apply(new StepResult("inspect-delivery-state", null, "Delivery state summarized.", "SUCCEEDED", false, 0));

        manager.record(exchange, "ACT_COMPLETED");

        AgentRunProjection projection = store.get(run.id());
        assertEquals(fullThought, projection.thoughtStack().getFirst().get("content"));
        String json = TestJson.write(projection);
        assertTrue(json.contains(fullThought.substring(0, 120)));
        assertFalse(json.contains("SECRET_TOKEN"));
        assertFalse(json.contains("Bearer SECRET_TOKEN"));
        assertFalse(json.contains("RAW_TOOL_PAYLOAD"));
        assertFalse(json.contains("Authorization"));
    }
}
