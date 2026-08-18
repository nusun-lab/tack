package us.nusun.llmshare.framework.tests.unit;

import org.apache.camel.Exchange;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.inventory.RoutineCatalog;
import us.nusun.llmshare.framework.model.AgentRunProjection;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.model.StepResult;
import us.nusun.llmshare.framework.model.ThoughtFrame;
import us.nusun.llmshare.framework.projection.AgentRunProjectionManager;
import us.nusun.llmshare.framework.projection.ProjectionStore;
import us.nusun.llmshare.framework.routine.ThoughtStackSupport;
import us.nusun.llmshare.framework.state.AgentRun;
import us.nusun.llmshare.framework.state.ThoughtStack;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;
import us.nusun.llmshare.framework.tests.support.TestJson;

import java.util.List;
import java.util.Map;

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
        run.apply(new StepResult("work-item-review", "Project membership is missing.", List.of()));
        exchange.setProperty("toolProfile", FrameworkFixtures.PROFILE_BETA);
        String fullPrompt = "Review one current item in the project management system. " + "Preserve this sentence. ".repeat(30);
        exchange.setProperty("primitivePrompt", fullPrompt);
        exchange.setProperty("laneId", "high-pass");
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
        assertEquals("act", projection.current().get("primitive"));
        assertEquals("work-item-review", projection.current().get("id"));
        assertEquals(FrameworkFixtures.PROFILE_BETA, projection.toolProfile());
        assertEquals("high-pass", projection.laneId());
        assertEquals(fullPrompt, projection.current().get("promptSummary"));
        assertEquals("step", projection.latestResult().get("type"));
        assertEquals("Project membership is missing.", projection.latestResult().get("summary"));
        assertEquals("ACT_COMPLETED", projection.timeline().getFirst().get("type"));
        assertTrue(projection.thoughtHistory().isEmpty());
        assertEquals(Boolean.TRUE, projection.thoughtStack().getFirst().get("truncated"));
        assertEquals("pm-cycle", projection.thoughtStack().getFirst().get("displayId"));
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
        run.currentPrimitive("act");
        run.currentId("inspect-delivery-state");
        run.apply(new StepResult("inspect-delivery-state", "Delivery state summarized.", List.of()));

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
