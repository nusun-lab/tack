package us.nusun.llmshare.framework.tests.unit;

import org.apache.camel.Exchange;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.state.AgentRun;
import us.nusun.llmshare.framework.model.StepResult;
import us.nusun.llmshare.framework.model.ThoughtFrame;
import us.nusun.llmshare.framework.model.AgentRunProjection;
import us.nusun.llmshare.framework.projection.AgentRunProjectionManager;
import us.nusun.llmshare.framework.projection.ProjectionStore;
import us.nusun.llmshare.framework.routine.PrimitiveSupport;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;
import us.nusun.llmshare.framework.state.ThoughtStack;
import us.nusun.llmshare.framework.routine.ThoughtStackSupport;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectionSemanticsTest {
    @Test
    void projectionBuildsDashboardSafeSnapshotFromLiveExchangeState() {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        run.currentPrimitive("act");
        run.currentId("work-item-review");
        run.apply(new StepResult("work-item-review", "blocked", "Project membership is missing.", List.of(), List.of(), List.of(), List.of(), List.of(), "needs-coordinator"));
        exchange.setProperty("toolProfile", "project-management");
        exchange.setProperty("actPrompt", "Review one current item in the project management system.");
        exchange.setProperty("resolvedToolRuntime", "tool-low");
        exchange.setProperty("resolvedSynthesisRuntime", "synthesis-high");
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("pm", "Project Manager routine context", 900, "Current state: ".repeat(120)));
        exchange.setProperty(ThoughtStackSupport.STACK_PROPERTY, stack);

        manager.record(exchange, "ACT_COMPLETED");

        AgentRunProjection projection = store.get(run.id());
        assertNotNull(projection);
        assertEquals(run.id(), projection.agentRunId());
        assertEquals(AgentRun.STATUS_RUNNING, projection.status());
        assertEquals(FrameworkFixtures.PERSONA_A, projection.persona().get("id"));
        assertEquals("PA", projection.persona().get("label"));
        assertEquals("38;2;106;169;255", projection.persona().get("color"));
        assertEquals(FrameworkFixtures.ROUTINE_A, projection.routine().get("id"));
        assertEquals("act", projection.current().get("primitive"));
        assertEquals("work-item-review", projection.current().get("id"));
        assertEquals("project-management", projection.toolProfile());
        assertEquals("tool-low", projection.runtimes().get("toolRuntime"));
        assertEquals("synthesis-high", projection.runtimes().get("synthesisRuntime"));
        assertEquals("low-pass", projection.lanes().get("toolLane"));
        assertEquals("high-pass", projection.lanes().get("synthesisLane"));
        assertEquals("step", projection.latestResult().get("type"));
        assertEquals("blocked", projection.latestResult().get("status"));
        assertEquals("Project membership is missing.", projection.latestResult().get("summary"));
        assertEquals("ACT_COMPLETED", projection.timeline().getFirst().get("type"));
        assertTrue(Boolean.TRUE.equals(projection.thoughtStack().getFirst().get("truncated")));
        assertTrue(String.valueOf(projection.thoughtStack().getFirst().get("contentPreview")).endsWith("[truncated]"));
        assertTrue(String.valueOf(projection.thoughtStack().getFirst().get("content")).length() > 1200);
    }

    @Test
    void projectionStoreReturnsMostRecentlyUpdatedProjectionFirst() throws InterruptedException {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        Exchange first = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        first.setProperty("agentRun", new AgentRun("run-first", "mission-1", FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A, java.util.Map.of()));
        Thread.sleep(2);
        Exchange second = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        second.setProperty("agentRun", new AgentRun("run-second", "mission-1", FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A, java.util.Map.of()));
        second.getProperty("agentRun", AgentRun.class).currentId("second");

        manager.record(first, "FIRST");
        Thread.sleep(2);
        manager.record(second, "SECOND");

        assertEquals(second.getProperty("agentRun", AgentRun.class).id(), store.all().getFirst().agentRunId());
    }

    @Test
    void frameworkProjectionEventsRecordPrimitivePassBoundariesAndFailureState() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        PrimitiveSupport support = FrameworkFixtures.primitiveSupport();
        FrameworkFixtures.set(support, "projections", manager);
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);
        run.currentPrimitive("act");
        run.currentId("inspect-delivery-state");
        exchange.setProperty("toolProfile", "project-management");
        exchange.setProperty("actPrompt", "Inspect current delivery state.");
        exchange.setProperty("resolvedToolRuntime", "tool-low");
        exchange.setProperty("resolvedSynthesisRuntime", "synthesis-low");
        exchange.setProperty("latestPrimitiveFailure", "tool pass failed: missing persona identity");

        support.projectionEvent("ACT_STARTED").process(exchange);
        support.projectionEvent("TOOL_PASS_QUEUED").process(exchange);
        support.projectionEvent("TOOL_PASS_COMPLETED").process(exchange);
        support.projectionEvent("SYNTHESIS_PASS_QUEUED").process(exchange);
        support.projectionEvent("SYNTHESIS_PASS_COMPLETED").process(exchange);
        support.projectionEvent("STEP_RESULT_APPLIED").process(exchange);
        support.projectionEvent("ACT_COMPLETED").process(exchange);

        AgentRunProjection projection = store.get(run.id());
        assertNotNull(projection);
        assertEquals("act", projection.current().get("primitive"));
        assertEquals("inspect-delivery-state", projection.current().get("id"));
        assertEquals("project-management", projection.toolProfile());
        assertEquals("tool-low", projection.runtimes().get("toolRuntime"));
        assertEquals("synthesis-low", projection.runtimes().get("synthesisRuntime"));
        assertEquals("low-pass", projection.lanes().get("toolLane"));
        assertEquals("low-pass", projection.lanes().get("synthesisLane"));
        assertEquals("failure", projection.latestResult().get("type"));
        assertEquals("tool pass failed: missing persona identity", projection.latestResult().get("summary"));
        assertEquals(List.of(
                "ACT_STARTED",
                "TOOL_PASS_QUEUED",
                "TOOL_PASS_COMPLETED",
                "SYNTHESIS_PASS_QUEUED",
                "SYNTHESIS_PASS_COMPLETED",
                "STEP_RESULT_APPLIED",
                "ACT_COMPLETED"), projection.timeline().stream().map(row -> row.get("type")).toList());
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
    void thoughtPushAndPopUpdateProjectionAutomatically() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        ThoughtStackSupport thoughts = new ThoughtStackSupport();
        FrameworkFixtures.set(thoughts, "projections", manager);
        Exchange exchange = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A);
        AgentRun run = exchange.getProperty("agentRun", AgentRun.class);

        thoughts.push("Project manager scoped context.", 400).process(exchange);
        AgentRunProjection pushed = store.get(run.id());
        assertNotNull(pushed);
        assertEquals("THOUGHT_PUSHED", pushed.timeline().getFirst().get("type"));
        assertEquals(1, pushed.thoughtStack().size());
        assertEquals("Project manager scoped context.", pushed.thoughtStack().getFirst().get("context"));
        assertEquals(0, pushed.thoughtStack().getFirst().get("approxTokens"));

        thoughts.pop().process(exchange);
        AgentRunProjection popped = store.get(run.id());
        assertEquals(List.of("THOUGHT_PUSHED", "THOUGHT_POPPED"), popped.timeline().stream().map(row -> row.get("type")).toList());
        assertTrue(popped.thoughtStack().isEmpty());
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
        exchange.setProperty("mcpHeaders", java.util.Map.of("Authorization", "Bearer SECRET_TOKEN"));
        exchange.setProperty("toolPassResult", "RAW_TOOL_PAYLOAD_" + "x".repeat(5000) + "_SECRET_TOKEN");
        run.currentPrimitive("act");
        run.currentId("inspect-delivery-state");
        run.apply(new StepResult("inspect-delivery-state", "complete", "Delivery state summarized.", List.of(), List.of(), List.of(), List.of(), List.of(), "continue"));

        manager.record(exchange, "STEP_RESULT_APPLIED");

        AgentRunProjection projection = store.get(run.id());
        assertNotNull(projection);
        assertEquals(fullThought, projection.thoughtStack().getFirst().get("content"));
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(projection);
        assertTrue(json.contains(fullThought.substring(0, 120)));
        assertFalse(json.contains("SECRET_TOKEN"));
        assertFalse(json.contains("Bearer SECRET_TOKEN"));
        assertFalse(json.contains("RAW_TOOL_PAYLOAD"));
        assertFalse(json.contains("Authorization"));
    }

}
